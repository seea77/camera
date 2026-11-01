package com.teacher.shotlab

import android.content.Context
import android.graphics.Color
import androidx.annotation.AttrRes

/** 按当前主题解析一个换肤属性对应的实际颜色 */
fun Context.attrColor(@AttrRes attr: Int): Int {
    val a = obtainStyledAttributes(intArrayOf(attr))
    val c = try {
        a.getColor(0, Color.WHITE)
    } finally {
        a.recycle()
    }
    return c
}
