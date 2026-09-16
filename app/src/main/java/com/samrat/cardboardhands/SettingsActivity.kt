package com.samrat.cardboardhands

import android.content.Intent
import android.os.Bundle
import android.provider.Settings as AndroidSettings
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.DynamicColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch

class SettingsActivity : ComponentActivity() {
    private var state = Settings.State()
    private lateinit var bindingList: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        DynamicColors.applyToActivityIfAvailable(this)
        super.onCreate(savedInstanceState)
        state = Settings.load(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(32), dp(20), dp(28))
        }
        root.addView(title("Настройки"), lp(bottom = 20))

        root.addView(card("Отслеживание", "6DoF — контроллеры двигаются в пространстве по камере. Выключите, если картинка дёргается: тогда останется только поворот, а рука будет держаться на постоянном расстоянии.") { content ->
            content.addView(switch("6DoF для контроллеров", state.sixDof) { enabled ->
                state = state.copy(sixDof = enabled)
                save()
            }, lp(top = 6))
        }, lp(bottom = 14))

        root.addView(card("Руки", "«Контроллеры» — жесты пальцев нажимают кнопки. «Только руки» — жесты ничего не нажимают, передаётся лишь положение и поворот рук.") { content ->
            content.addView(toggle(
                listOf("Контроллеры", "Только руки"),
                if (state.handMode == Settings.HandMode.CONTROLLERS) 0 else 1
            ) { index ->
                state = state.copy(
                    handMode = if (index == 0) Settings.HandMode.CONTROLLERS else Settings.HandMode.HANDS
                )
                save()
            }, lp(top = 6))
        }, lp(bottom = 14))

        root.addView(card("Joy‑Con", "Раскладка кнопок. «Quest» повторяет контроллеры Quest, «Nintendo» ставит нижнюю кнопку на курок. Любую кнопку можно переназначить ниже.") { content ->
            content.addView(toggle(
                listOf("Quest", "Nintendo"),
                if (state.layout == Settings.Layout.QUEST) 0 else 1
            ) { index ->
                state = state.copy(
                    layout = if (index == 0) Settings.Layout.QUEST else Settings.Layout.NINTENDO,
                    bindings = emptyMap()
                )
                save()
                showBindings()
            }, lp(top = 6, bottom = 10))
            bindingList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            content.addView(bindingList, lp())
        }, lp(bottom = 14))

        root.addView(outlined("Кнопки Joy‑Con: включить перехват") {
            startActivity(Intent(AndroidSettings.ACTION_ACCESSIBILITY_SETTINGS))
        }, lp(height = 52, bottom = 14))
        root.addView(outlined("О приложении") {
            startActivity(Intent(this, AboutActivity::class.java))
        }, lp(height = 52))

        showBindings()
        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun showBindings() {
        bindingList.removeAllViews()
        Settings.Button.entries.forEach { button ->
            val action = state.actionFor(button)
            bindingList.addView(outlined("${button.title}  →  ${action.title}") {
                chooseAction(button)
            }, lp(height = 48, bottom = 8))
        }
    }

    private fun chooseAction(button: Settings.Button) {
        val actions = Settings.Action.entries
        val current = actions.indexOf(state.actionFor(button))
        MaterialAlertDialogBuilder(this)
            .setTitle(button.title)
            .setSingleChoiceItems(actions.map { it.title }.toTypedArray(), current) { dialog, index ->
                state = state.copy(bindings = state.bindings + (button to actions[index]))
                save()
                showBindings()
                dialog.dismiss()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun save() = Settings.save(this, state)

    private fun card(name: String, description: String, fill: (LinearLayout) -> Unit): MaterialCardView {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(16))
            addView(TextView(this@SettingsActivity).apply { text = name; textSize = 20f }, lp(bottom = 6))
            addView(TextView(this@SettingsActivity).apply {
                text = description
                textSize = 13f
                alpha = .75f
            }, lp(bottom = 4))
        }
        fill(content)
        return MaterialCardView(this).apply {
            radius = dp(24).toFloat()
            strokeWidth = dp(1)
            addView(content)
        }
    }

    private fun title(text: String) = TextView(this).apply {
        this.text = text
        textSize = 32f
    }

    private fun switch(text: String, checked: Boolean, onChange: (Boolean) -> Unit) =
        MaterialSwitch(this).apply {
            this.text = text
            textSize = 16f
            isChecked = checked
            setOnCheckedChangeListener { _, value -> onChange(value) }
        }

    private fun toggle(options: List<String>, selected: Int, onChange: (Int) -> Unit) =
        MaterialButtonToggleGroup(this).apply {
            isSingleSelection = true
            isSelectionRequired = true
            options.forEachIndexed { index, option ->
                addView(MaterialButton(this@SettingsActivity, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                    text = option
                    id = index + 1
                }, LinearLayout.LayoutParams(0, dp(48), 1f))
            }
            check(selected + 1)
            addOnButtonCheckedListener { _, id, isChecked -> if (isChecked) onChange(id - 1) }
        }

    private fun outlined(text: String, action: () -> Unit) =
        MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            this.text = text
            textSize = 14f
            cornerRadius = dp(16)
            setOnClickListener { action() }
        }

    private fun lp(width: Int = ViewGroup.LayoutParams.MATCH_PARENT, height: Int = -2, top: Int = 0, bottom: Int = 0) =
        LinearLayout.LayoutParams(width, if (height > 0) dp(height) else height).apply {
            setMargins(0, dp(top), 0, dp(bottom))
        }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
