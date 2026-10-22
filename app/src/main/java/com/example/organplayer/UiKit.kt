package com.example.organplayer

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

object UiKit {
    @Volatile var dark = false                     // app theme (set by MainActivity)
    const val AMBER = 0xFFFFB300.toInt()          // "ON" indicator colour (yellow-orange)

    fun dp(ctx: Context, v: Int) = (v * ctx.resources.displayMetrics.density).toInt()

    fun shape(ctx: Context, fill: Int, stroke: Int, radiusDp: Int, strokeDp: Int = 1) = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = dp(ctx, radiusDp).toFloat()
        setStroke(dp(ctx, strokeDp), stroke)
    }

    /** Lights a button amber (selected / on) or returns it to a neutral look. */
    fun setLit(b: TextView, lit: Boolean, dark: Boolean = UiKit.dark) {
        val off = if (dark) 0xFF23262D.toInt() else 0xFFE0E0E0.toInt()
        b.background = shape(b.context, if (lit) AMBER else off, if (lit) 0xFFFFD54F.toInt() else 0x33000000, 8)
        b.setTextColor(if (lit) 0xFF1A1200.toInt() else if (dark) 0xFFC9CED6.toInt() else 0xFF111111.toInt())
    }

    fun button(ctx: Context, text: String, onClick: () -> Unit) = Button(ctx).apply {
        this.text = text
        isAllCaps = false
        textSize = 12f
        minHeight = 0; minimumHeight = dp(ctx, 38)
        minWidth = 0; minimumWidth = 0
        setPadding(dp(ctx, 8), 0, dp(ctx, 8), 0)
        setOnClickListener { onClick() }
    }

    fun header(ctx: Context, text: String) = TextView(ctx).apply {
        this.text = text
        textSize = 15f
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(ctx, 14), 0, dp(ctx, 4))
    }

    /** Maliit na button na nagpapakita ng kasalukuyang MIDI mapping; pindutin para mag-learn. */
    fun mapButton(act: Activity, c: OrganController, action: String, title: String): Button {
        val b = c.midiMap.get(action)
        return button(act, b?.label() ?: "MIDI") { MidiLearn.show(act, c, action, title) }.apply {
            if (b != null) setTypeface(typeface, Typeface.BOLD)
        }
    }
}

/** Numeric na input: i-type ang numero, o gamitin ang - / + . Nagva-validate sa min..max. */
class NumberField(
    ctx: Context, label: String, private val min: Int, private val max: Int, unit: String,
    value: Int, private val step: Int = 1, private val onChange: (Int) -> Unit
) : LinearLayout(ctx) {
    private var current = value.coerceIn(min, max)
    private val edit = EditText(ctx)

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(TextView(ctx).apply { text = label; textSize = 14f }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        addView(UiKit.button(ctx, "-") { bump(-step) }, LinearLayout.LayoutParams(UiKit.dp(ctx, 44), WRAP_CONTENT))
        edit.apply {
            setText(current.toString())
            inputType = InputType.TYPE_CLASS_NUMBER or (if (min < 0) InputType.TYPE_NUMBER_FLAG_SIGNED else 0)
            gravity = Gravity.CENTER
            setSingleLine()
            setSelectAllOnFocus(true)
            imeOptions = EditorInfo.IME_ACTION_DONE
            setOnEditorActionListener { v, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_DONE) {
                    commit(); v.clearFocus()
                    (ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                        .hideSoftInputFromWindow(v.windowToken, 0)
                    true
                } else false
            }
            setOnFocusChangeListener { _, focused -> if (!focused) commit() }
        }
        addView(edit, LinearLayout.LayoutParams(UiKit.dp(ctx, 76), WRAP_CONTENT))
        addView(UiKit.button(ctx, "+") { bump(step) }, LinearLayout.LayoutParams(UiKit.dp(ctx, 44), WRAP_CONTENT))
        if (unit.isNotEmpty()) {
            addView(TextView(ctx).apply { text = " $unit"; textSize = 12f }, LinearLayout.LayoutParams(UiKit.dp(ctx, 52), WRAP_CONTENT))
        }
    }

    /** Basahin ang tinype, i-validate, at ipadala kung nagbago. */
    fun commit() {
        val v = (edit.text.toString().trim().toIntOrNull() ?: current).coerceIn(min, max)
        edit.setText(v.toString())
        if (v != current) { current = v; onChange(v) }
    }

    private fun bump(d: Int) {
        commit()
        val v = (current + d).coerceIn(min, max)
        edit.setText(v.toString())
        if (v != current) { current = v; onChange(v) }
    }

    /** Update mula sa labas (hindi tinatawag ang onChange). */
    fun setValue(v: Int) {
        if (edit.hasFocus()) return
        current = v.coerceIn(min, max)
        edit.setText(current.toString())
    }
}

object MidiLearn {
    fun show(act: Activity, c: OrganController, action: String, title: String) {
        c.midiMap.learning = action
        val dlg = AlertDialog.Builder(act)
            .setTitle(tr("MIDI mapping", "MIDI mapping"))
            .setMessage("$title\n\n" + tr("Press a key, piston or knob on your MIDI device to assign it.", "Pindutin ang key, piston o knob sa MIDI device para i-assign dito."))
            .setNeutralButton(tr("Clear mapping", "Burahin ang mapping")) { _, _ -> c.midiMap.clear(action) }
            .setNegativeButton(tr("Cancel", "Kanselahin"), null)
            .create()
        c.midiMap.onLearned = { dlg.dismiss() }
        dlg.setOnDismissListener {
            c.midiMap.learning = null
            c.midiMap.onLearned = null
            c.notifyChanged()
        }
        dlg.show()
    }
}
