#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
羽光相机 声控拍照 —— TFLite 微型关键词唤醒(KWS) 训练脚本（频谱输入版）。

只识别 4 个口令 + 1 个背景类：
    0 = 其他(背景)   1 = 拍照   2 = 拍张照   3 = 咔嚓   4 = 茄子

设计要点（与 Android 端严格一致，零特征差异）：
  * STFT + Mel + log 全部在 Android 端用 Kotlin 计算；训练端用等价的 numpy 实现，
    二者共用同一套参数(FRAME_LEN/STEP/N_FFT/N_MEL)与同一份 mel 矩阵。
  * 模型输入是 log-Mel 频谱图 [N_FRAMES, N_MEL, 1]，只含 Conv2D/BN/Pool/Dense 等
    TFLite 原生算子，可直接导出 kws.tflite，手机能跑且体积 <1MB。
  * mel 矩阵由本脚本用 tf.signal.linear_to_mel_weight_matrix 算一次，导出为
    kws_mel.txt，Android 直接加载，避免两端各自复现复杂公式出错。
  * 训练样本全部由合成语音(TTS)生成：优先 edge-tts(自然声)，不可达时回退 espeak-ng。

产物（写入 --out 所在的 assets 目录）：
  kws.tflite   —— 模型
  kws_mel.txt  —— mel 矩阵(N_MEL 行 × N_BINS 列, 行优先, 空格分隔)
  kws_spec.json—— 前端参数(供 Android 校验一致性)

用法：
  python3 tools/train_kws.py --out app/src/main/assets/kws.tflite --epochs 40 --samples-per-kw 40
"""
import argparse
import asyncio
import json
import os
import random

import numpy as np
import tensorflow as tf

# --------------------------- 前端参数（与 Android 端严格一致）---------------------------
SR = 16000
WIN = SR                       # 1.0 秒 = 16000 采样点
FRAME_LEN = 400               # 25ms
FRAME_STEP = 160              # 10ms
N_FFT = 512
N_BINS = N_FFT // 2 + 1       # 257
N_MEL = 40
F_MIN = 20.0
F_MAX = 8000.0
N_FRAMES = 1 + (WIN - FRAME_LEN) // FRAME_STEP   # 1 + 15600//160 = 98

N_CLASSES = 5
KEYWORDS = ["拍照", "拍张照", "咔嚓", "茄子"]   # 类别 1..4
NEG_WORDS = ["一二三四五六七八九十", "开始", "停止", "你好", "谢谢",
             "打开", "关闭", "向左", "向右", "放大", "缩小", "确认",
             "取消", "上一页", "下一页", "返回", "确定", "完成", "继续", "暂停"]

CACHE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "kws_cache")


# ----------------------------------------------------------------------------
# 1. 合成语音样本
# ----------------------------------------------------------------------------
def gen_with_edgetts(text, path, voice, pitch=0, rate=0):
    import edge_tts
    ssml = (f'<speak version="1.0" xmlns="http://www.w3.org/2001/10/synthesis" '
            f'xml:lang="zh-CN"><voice name="{voice}"><prosody pitch="{pitch:+d}Hz" '
            f'rate="{rate:+d}%">{text}</prosody></voice></speak>')
    async def _run():
        comm = edge_tts.Communicate(ssml, voice, rate=f"{rate:+d}%", pitch=f"{pitch:+d}Hz")
        await comm.save(path)
    asyncio.run(_run())


def gen_with_espeak(text, path, voice=None, pitch=0, rate=0):
    import subprocess
    p = max(0, min(99, 50 + pitch // 8))
    s = max(80, min(450, 175 + rate * 2))
    with open(path, "wb") as f:
        subprocess.run(["espeak-ng", "-v", "zh", "-p", str(p), "-s", str(s),
                        text, "--stdout"], stdout=f, check=True)


def tts_gen(text, path, seed):
    voices = ["zh-CN-XiaoxiaoNeural", "zh-CN-YunyangNeural",
              "zh-CN-XiaoyiNeural", "zh-CN-YunxiNeural"]
    rnd = random.Random(seed)
    voice = rnd.choice(voices)
    pitch = rnd.choice([-12, -6, 0, 6, 12])
    rate = rnd.choice([-15, 0, 15])
    try:
        gen_with_edgetts(text, path, voice, pitch, rate)
        return "edge-tts"
    except Exception:
        try:
            gen_with_espeak(text, path, None, pitch, rate)
            return "espeak-ng"
        except Exception as e2:
            raise RuntimeError(f"TTS 失败: {e2}")


def build_corpus(samples_per_kw):
    os.makedirs(CACHE, exist_ok=True)
    meta = {"pos": [], "neg": [], "engine": set()}
    idx = 0
    for ci, kw in enumerate(KEYWORDS, start=1):
        for i in range(samples_per_kw):
            p = os.path.join(CACHE, f"pos_{ci}_{i}.wav")
            if not os.path.exists(p):
                try:
                    eng = tts_gen(kw, p, seed=idx)
                    meta["engine"].add(eng)
                except Exception as e:
                    print(f"  [warn] 跳过生成失败: {p}: {e}")
                    idx += 1
                    continue
            meta["pos"].append((p, ci))
            idx += 1
    n_neg = samples_per_kw * len(KEYWORDS)
    per = max(1, n_neg // len(NEG_WORDS))
    done = 0
    for w in NEG_WORDS:
        for _ in range(per):
            p = os.path.join(CACHE, f"neg_{done}.wav")
            if not os.path.exists(p):
                try:
                    eng = tts_gen(w, p, seed=idx)
                    meta["engine"].add(eng)
                except Exception as e:
                    print(f"  [warn] 跳过生成失败: {p}: {e}")
                    idx += 1
                    done += 1
                    continue
            meta["neg"].append((p, 0))
            done += 1
            idx += 1
            if done >= n_neg:
                break
        if done >= n_neg:
            break
    # 噪声：幅度跨度取对数均匀分布(0.002~0.2)，覆盖真实环境的各种底噪强度
    for i in range(max(30, samples_per_kw)):
        p = os.path.join(CACHE, f"noise_{i}.wav")
        if not os.path.exists(p):
            dur = np.random.uniform(0.6, 1.0)
            n = int(dur * SR)
            amp = float(np.exp(np.random.uniform(np.log(0.002), np.log(0.2))))
            sig = np.random.randn(n).astype(np.float32) * amp
            write_wav(p, sig)
        meta["neg"].append((p, 0))

    # 静音：纯零 + 极微弱底噪。
    # 关键：开着声控但没说话时，麦克风输入就是接近纯静音，其 log-Mel 全为
    # log(1e-6)≈-13.8。若训练集没有这类样本，模型遇到静音会因分布外而乱判成
    # 关键词，表现为「什么也没说却不停拍照」。必须显式教它把静音判为背景类。
    for i in range(max(40, samples_per_kw)):
        p = os.path.join(CACHE, f"silence_{i}.wav")
        if not os.path.exists(p):
            if i % 4 == 0:
                sig = np.zeros(WIN, np.float32)                     # 纯静音
            else:
                amp = float(np.exp(np.random.uniform(np.log(1e-5), np.log(5e-3))))
                sig = np.random.randn(WIN).astype(np.float32) * amp  # 极微弱底噪
            write_wav(p, sig)
        meta["neg"].append((p, 0))
    print(f"[corpus] 正样本 {len(meta['pos'])} 条，负样本 {len(meta['neg'])} 条，TTS 引擎: {meta['engine']}")
    return meta


# ----------------------------------------------------------------------------
# 2. 音频 IO
# ----------------------------------------------------------------------------
def write_wav(path, sig):
    import soundfile as sf
    sf.write(path, sig, SR)


def load_wav(path):
    import soundfile as sf
    import scipy.signal as ss
    data, sr = sf.read(path, dtype="float32")
    if data.ndim > 1:
        data = data.mean(axis=1)
    if sr != SR:
        n = int(round(len(data) * SR / sr))
        data = ss.resample_poly(data, SR, sr).astype(np.float32)[:n]
    data = np.asarray(data, dtype=np.float32)
    peak = np.max(np.abs(data)) + 1e-6
    if peak > 1.0:
        data = data / peak
    if len(data) > WIN:
        start = (len(data) - WIN) // 2
        data = data[start:start + WIN]
    elif len(data) < WIN:
        pad = WIN - len(data)
        left = pad // 2
        data = np.concatenate([np.zeros(left, np.float32), data, np.zeros(pad - left, np.float32)])
    return data


# ----------------------------------------------------------------------------
# 3. 特征（numpy 实现，与 Android 端 Kotlin 严格一致）
#    汉宁窗使用 symmetric 公式：win[n] = 0.5 - 0.5*cos(2π n/(N-1))，n=0..N-1
# ----------------------------------------------------------------------------
def compute_mel_matrix():
    """(N_MEL, N_BINS) 线性频率→Mel 映射矩阵，供训练与 Android 两端共用。

    注意：tf.signal.linear_to_mel_weight_matrix 实际返回 (N_BINS, N_MEL)，
    与文档标注相反，这里转置成 (N_MEL, N_BINS) 以便与幅度谱 (N_BINS,) 相乘。
    """
    mel = tf.signal.linear_to_mel_weight_matrix(N_MEL, N_BINS, SR, F_MIN, F_MAX)
    return mel.numpy().astype(np.float32).T


_WIN_HANN = (0.5 - 0.5 * np.cos(2 * np.pi * np.arange(FRAME_LEN) / (FRAME_LEN - 1))).astype(np.float32)


def compute_logmel(wav, mel_mat):
    """wav: float32 [WIN] -> log-Mel 频谱图 float32 [N_FRAMES, N_MEL]。"""
    frames = np.empty((N_FRAMES, N_MEL), dtype=np.float32)
    for t in range(N_FRAMES):
        start = t * FRAME_STEP
        seg = wav[start:start + FRAME_LEN] * _WIN_HANN
        spec = np.fft.rfft(seg, n=N_FFT)          # 复数 (N_BINS,)
        mag = np.abs(spec).astype(np.float32)      # (N_BINS,)
        mel = mel_mat @ mag                         # (N_MEL,)
        frames[t] = mel
    logmel = np.log(frames + 1e-6).astype(np.float32)
    return logmel


# ----------------------------------------------------------------------------
# 4. 数据流水线（样本预读进内存，直接 from_tensor_slices，绕开 numpy_function 死锁）
# ----------------------------------------------------------------------------
def load_all(meta):
    mel = compute_mel_matrix()
    X, Y = [], []
    for p, c in meta["pos"] + meta["neg"]:
        X.append(compute_logmel(load_wav(p), mel))
        Y.append(c)
    X = np.stack(X).astype(np.float32)[..., None]   # (N, N_FRAMES, N_MEL, 1)
    Y = np.array(Y, dtype=np.int32)
    return X, Y


def _mask_freq(s):
    f = tf.random.uniform((), 0, 8, tf.int32)
    f0 = tf.random.uniform((), 0, N_MEL - f, tf.int32)
    return tf.concat([s[:, :f0], tf.zeros((N_FRAMES, f)), s[:, f0 + f:]], axis=1)


def _mask_time(s):
    t = tf.random.uniform((), 0, 10, tf.int32)
    t0 = tf.random.uniform((), 0, N_FRAMES - t, tf.int32)
    return tf.concat([s[:t0], tf.zeros((t, N_MEL)), s[t0 + t:]], axis=0)


def spec_aug(spec, label):
    s = tf.reshape(spec, (N_FRAMES, N_MEL))
    s = tf.cond(tf.random.uniform(()) < 0.5, lambda: _mask_freq(s), lambda: s)
    s = tf.cond(tf.random.uniform(()) < 0.5, lambda: _mask_time(s), lambda: s)
    s = s + tf.random.normal(tf.shape(s), 0.0, 0.05)   # 轻微加噪
    # 不要加「log 域整体常数偏移」来模拟音量变化：静音与语音的关键区别正是能量
    # 水平，全局偏移会抹掉这一判别信息，实测让误触发率从 0.035 恶化到 0.171，
    # 关键词召回也明显下降。音量鲁棒性交由端上处理。
    return tf.expand_dims(s, -1), tf.one_hot(label, N_CLASSES)


def build_pipeline(X, Y, batch, train=True):
    ds = tf.data.Dataset.from_tensor_slices((X, Y))
    if train:
        ds = ds.shuffle(min(4096, len(X)))
        ds = ds.repeat()
        ds = ds.map(spec_aug, num_parallel_calls=4)
        ds = ds.batch(batch).prefetch(2)
    else:
        ds = ds.map(lambda s, l: (s, tf.one_hot(l, N_CLASSES)), num_parallel_calls=4)
        ds = ds.batch(batch)
    return ds


# ----------------------------------------------------------------------------
# 5. 模型（纯 CNN，全 TFLite 原生算子）
# ----------------------------------------------------------------------------
def build_model():
    inp = tf.keras.Input(shape=(N_FRAMES, N_MEL, 1), dtype=tf.float32, name="logmel")
    x = tf.keras.layers.Conv2D(32, 3, padding="same", activation="relu")(inp)
    x = tf.keras.layers.BatchNormalization(momentum=0.9)(x)
    x = tf.keras.layers.MaxPool2D(2)(x)                       # (49,20,32)
    x = tf.keras.layers.Conv2D(64, 3, padding="same", activation="relu")(x)
    x = tf.keras.layers.BatchNormalization(momentum=0.9)(x)
    x = tf.keras.layers.MaxPool2D(2)(x)                       # (24,10,64)
    x = tf.keras.layers.Conv2D(96, 3, padding="same", activation="relu")(x)
    x = tf.keras.layers.BatchNormalization(momentum=0.9)(x)               # (24,10,96)
    # 不用 GlobalAveragePooling2D：TF2.16 的 MLIR 转换对其有 “Failed to infer
    # result type(s)” 崩溃 bug。改用固定核 AveragePooling2D 把 24×10 压成 1×1，
    # 等价全局平均，且全是 TFLite 原生 op。
    x = tf.keras.layers.AveragePooling2D((24, 10))(x)         # (1,1,96)
    x = tf.keras.layers.Reshape((96,))(x)
    x = tf.keras.layers.Dense(96, activation="relu")(x)
    out = tf.keras.layers.Dense(N_CLASSES, activation="softmax")(x)
    m = tf.keras.Model(inp, out, name="kws")
    m.compile(optimizer=tf.keras.optimizers.Adam(1e-3),
              loss="categorical_crossentropy", metrics=["accuracy"])
    return m


# ----------------------------------------------------------------------------
# 6. 导出 TFLite + mel 矩阵 + 规格 json
# ----------------------------------------------------------------------------
def export_tflite(model, out_path):
    """导出为固定 batch=1 的 TFLite 模型。

    注意：TF2.16 的 MLIR 转换路径对「动态 batch + 量化优化」偶发
    LLVM ERROR: Failed to infer result type(s)。这里用 from_concrete_functions
    把输入固定为 [1, N_FRAMES, N_MEL, 1]，并暂不做量化（float32 模型约 330KB，
    远小于容忍体量；如需更小后续再单独加量化 pass）。
    """
    @tf.function(input_signature=[tf.TensorSpec([1, N_FRAMES, N_MEL, 1], tf.float32)])
    def serve(x):
        return model(x, training=False)

    conv = tf.lite.TFLiteConverter.from_concrete_functions([serve.get_concrete_function()])
    tflite = conv.convert()
    with open(out_path, "wb") as f:
        f.write(tflite)
    print(f"[export] {out_path}  ({len(tflite) / 1024:.1f} KB)")


# ----------------------------------------------------------------------------
def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default="app/src/main/assets/kws.tflite")
    ap.add_argument("--epochs", type=int, default=40)
    ap.add_argument("--samples-per-kw", type=int, default=40)
    ap.add_argument("--batch", type=int, default=32)
    args = ap.parse_args()

    tf.get_logger().setLevel("ERROR")
    gpus = tf.config.list_physical_devices("GPU")
    for g in gpus:
        tf.config.experimental.set_memory_growth(g, True)

    print("[1/6] 生成合成语音语料 ...")
    meta = build_corpus(args.samples_per_kw)

    print("[2/6] 计算 mel 矩阵 + 预读语料为 log-Mel 频谱 ...")
    mel = compute_mel_matrix()
    X, Y = load_all(meta)
    print(f"    样本矩阵 X={X.shape} Y={Y.shape}")

    train_ds = build_pipeline(X, Y, args.batch, train=True)

    print("[3/6] 构建并训练模型 ...")
    model = build_model()
    model.summary(print_fn=lambda s: print("    " + s))
    # 训练步数要足够多：BN 的 moving 统计量需大量步数才收敛，
    # 否则推理态(training=False)会与训练态严重不一致（手机上运行正是推理态）。
    # 不再用 val_ds/EarlyStopping：验证集与训练集同源，且有限 val_ds 会触发
    # "ran out of data"。最终质量以 [4/6] 的全量推理态评估为准。
    steps = max(20, len(X) // args.batch)
    model.fit(train_ds, steps_per_epoch=steps, epochs=args.epochs, verbose=2)

    print("[4/6] 评估（推理态 training=False，与手机运行一致）...")
    prob = model(X, training=False).numpy()          # (N, N_CLASSES) 概率
    pred = prob.argmax(axis=1)
    names = ["其他", "拍照", "拍张照", "咔嚓", "茄子"]
    cm = np.zeros((N_CLASSES, N_CLASSES), dtype=int)
    for t, p in zip(Y, pred):
        cm[t][p] += 1
    acc = np.trace(cm) / cm.sum()
    print(f"    整体 accuracy = {acc:.4f}   "
          f"(负类占比 {np.mean(Y == 0):.3f}，若 accuracy 接近该值说明又塌成全猜背景)")
    for ci, name in enumerate(names):
        tot = int(cm[ci].sum())
        tp = int(cm[ci][ci])
        prec = tp / (int(cm[:, ci].sum()) + 1e-9)
        rec = tp / (tot + 1e-9)
        conf = float(prob[Y == ci, ci].mean()) if tot else 0.0
        print(f"    {name}: recall={rec:.3f} ({tp}/{tot}) precision={prec:.3f} "
              f"平均置信度={conf:.3f}")
    far = float(np.mean(pred[Y == 0] > 0))
    print(f"    负类误触发率 FAR = {far:.4f}")

    print("[5/6] 导出 TFLite + mel 矩阵 + 规格 ...")
    asset_dir = os.path.dirname(os.path.abspath(args.out))
    os.makedirs(asset_dir, exist_ok=True)
    export_tflite(model, args.out)
    np.savetxt(os.path.join(asset_dir, "kws_mel.txt"), mel, fmt="%.6f", delimiter=" ")
    spec = dict(SR=SR, WIN=WIN, FRAME_LEN=FRAME_LEN, FRAME_STEP=FRAME_STEP,
                N_FFT=N_FFT, N_BINS=N_BINS, N_MEL=N_MEL, F_MIN=F_MIN, F_MAX=F_MAX,
                N_FRAMES=N_FRAMES, N_CLASSES=N_CLASSES)
    with open(os.path.join(asset_dir, "kws_spec.json"), "w") as f:
        json.dump(spec, f, indent=2, ensure_ascii=False)
    print(f"[export] kws_mel.txt / kws_spec.json -> {asset_dir}")
    print("完成。")


if __name__ == "__main__":
    main()
