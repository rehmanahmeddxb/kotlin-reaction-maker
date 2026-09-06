package com.rehman.ahmedreactionstudio.editor

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import com.rehman.ahmedreactionstudio.util.UI

class EffectsPanel(context: Context) : LinearLayout(context) {
    init {
        orientation = VERTICAL
        setBackgroundColor(Color.rgb(18, 20, 26))
        
        val emptyText = TextView(context).apply {
            text = "Select a source to apply effects"
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(UI.dp(context, 16), UI.dp(context, 16), UI.dp(context, 16), UI.dp(context, 16))
        }
        addView(emptyText, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }
}
