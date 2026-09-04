package com.rehman.ahmedreactionstudio.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import com.rehman.ahmedreactionstudio.core.Aspect
import com.rehman.ahmedreactionstudio.core.Project
import com.rehman.ahmedreactionstudio.core.ProjectStore
import com.rehman.ahmedreactionstudio.editor.EditorActivity
import com.rehman.ahmedreactionstudio.util.UI
import java.io.File

class HomeActivity : Activity() {

    private lateinit var store: ProjectStore
    private val projects = ArrayList<Project>()
    private lateinit var adapter: ProjectsAdapter

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        UI.styleWindow(this)
        store = ProjectStore(this)
        store.ensureRoot()
        buildUi()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        projects.clear()
        for (id in store.listIds()) {
            store.load(id)?.let { projects.add(it) }
        }
        adapter.notifyDataSetChanged()
    }

    private fun buildUi() {
        val root = UI.col(this, true)
        root.setPadding(UI.dp(this, 16), UI.dp(this, 22), UI.dp(this, 16), 0)

        // ---- header
        val header = UI.col(this, false)
        val logo = TextView(this)
        logo.text = "\u25B6"
        logo.setTextColor(UI.ACCENT)
        logo.textSize = 26f
        logo.gravity = Gravity.CENTER
        val lg = GradientDrawable()
        lg.cornerRadius = UI.dpf(this, 10f)
        lg.setColor(Color.argb(40, 255, 90, 44))
        logo.background = lg
        val lp = LinearLayout.LayoutParams(UI.dp(this, 46), UI.dp(this, 46))
        logo.layoutParams = lp
        header.addView(logo)

        val tt = UI.col(this, true)
        tt.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        val name = UI.title(this, "Ahmed Reaction Studio")
        tt.addView(name)
        val sub = UI.label(this, "Local-first Kotlin reaction & PiP editor", dim = true, size = 12f)
        tt.addView(sub)
        header.addView(tt)

        val diagBtn = UI.chip(this, "\u2699")
        diagBtn.setOnClickListener {
            startActivity(Intent(this, DiagnosticsActivity::class.java))
        }
        header.addView(diagBtn)
        root.addView(header)

        val hint = UI.label(this,
            "Projects are stored on this device only. No accounts, no cloud.",
            dim = true, size = 11f)
        UI.margin(hint, 0, 14, 0, 0, this)
        root.addView(hint)

        // ---- list
        val list = ListView(this)
        list.divider = null
        list.setPadding(0, UI.dp(this, 6), 0, 0)
        list.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        adapter = ProjectsAdapter()
        list.adapter = adapter
        list.onItemClickListener = AdapterView.OnItemClickListener { _, _, pos, _ ->
            openProject(projects[pos].id)
        }
        root.addView(list)

        // ---- new project button
        val newBtn = UI.btn(this, "+  New project", accent = true)
        newBtn.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, UI.dp(this, 50))
        newBtn.setOnClickListener { showNewDialog() }
        root.addView(newBtn)

        setContentView(root)
    }

    private fun openProject(id: String) {
        val p = store.load(id) ?: return
        val i = Intent(this, EditorActivity::class.java)
        i.putExtra(EditorActivity.EXTRA_PROJECT_ID, id)
        i.putExtra(EditorActivity.EXTRA_PROJECT_NAME, p.name)
        i.putExtra(EditorActivity.EXTRA_PROJECT_ASPECT, p.aspect.code)
        store.markOpen(id)
        startActivity(i)
    }

    private fun showNewDialog() {
        val holder = LinearLayout(this)
        holder.orientation = LinearLayout.VERTICAL
        holder.setPadding(UI.dp(this, 22), UI.dp(this, 8), UI.dp(this, 22), 0)

        val nameInput = EditText(this)
        nameInput.hint = "Project name"
        nameInput.setText("My Reaction")
        nameInput.setTextColor(UI.FG)
        nameInput.setHintTextColor(Color.argb(150, 255, 255, 255))
        holder.addView(nameInput)

        val aspectRow = UI.col(this, false)
        aspectRow.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        UI.margin(aspectRow, 0, 14, 0, 6, this)
        val chips = HashMap<Aspect, TextView>()
        val rowLp = LinearLayout.LayoutParams(0, UI.dp(this, 40), 1f)
        for (a in Aspect.entries) {
            val c = UI.chip(this, a.code)
            c.layoutParams = rowLp
            c.setOnClickListener {
                val sel = chips.values.firstOrNull { it === c } ?: return@setOnClickListener
                for ((k, v) in chips) v.isSelected = (v === c)
                refreshChips(chips)
            }
            chips[a] = c
            aspectRow.addView(c)
        }
        holder.addView(aspectRow)

        val dlg = AlertDialog.Builder(this)
            .setTitle("New project")
            .setView(holder)
            .setPositiveButton("Create") { _, _ ->
                val name = nameInput.text.toString()
                val aspect = chips.entries.firstOrNull { it.value.isSelected }?.key ?: Aspect.R169
                val p = store.create(name, aspect)
                openProject(p.id)
            }
            .setNegativeButton("Cancel", null)
            .show()
        // default select 16:9 landscape (classic YouTube reaction canvas)
        chips[Aspect.R169]?.isSelected = true
        refreshChips(chips)
    }

    private fun refreshChips(chips: HashMap<Aspect, TextView>) {
        for ((a, v) in chips) {
            val g = v.background as GradientDrawable
            if (v.isSelected) {
                g.setColor(UI.ACCENT)
                g.setStroke(UI.dp(this, 1), Color.argb(140, 255, 220, 180))
                v.setTextColor(Color.WHITE)
            } else {
                g.setColor(UI.BG3)
                g.setStroke(UI.dp(this, 1), Color.argb(60, 255, 255, 255))
                v.setTextColor(UI.FG)
            }
            v.background = g
        }
    }

    private inner class ProjectsAdapter : BaseAdapter() {
        override fun getCount(): Int = projects.size
        override fun getItem(pos: Int): Any = projects[pos]
        override fun getItemId(pos: Int): Long = pos.toLong()

        override fun getView(pos: Int, convert: View?, parent: ViewGroup?): View {
            val ctx = this@HomeActivity
            val p = projects[pos]
            val card = LinearLayout(ctx)
            card.orientation = LinearLayout.HORIZONTAL
            card.setPadding(UI.dp(ctx, 12), UI.dp(ctx, 10), UI.dp(ctx, 12), UI.dp(ctx, 10))
            val g = GradientDrawable()
            g.cornerRadius = UI.dpf(ctx, 14f)
            g.setColor(UI.BG2)
            g.setStroke(UI.dp(ctx, 1), Color.argb(50, 255, 255, 255))
            card.background = g

            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, UI.dp(ctx, 92))
            lp.setMargins(0, 0, 0, UI.dp(ctx, 8))
            card.layoutParams = lp

            // thumb
            val frame = FrameLayout(ctx)
            val flp = LinearLayout.LayoutParams(UI.dp(ctx, 66), UI.dp(ctx, 66))
            frame.layoutParams = flp
            val thumb = ImageView(ctx)
            val imgFile = store.thumbFile(p.id)
            val fg = GradientDrawable()
            fg.cornerRadius = UI.dpf(ctx, 8f)
            fg.setColor(UI.BG3)
            frame.background = fg
            if (imgFile.exists()) {
                try {
                    val bmp = BitmapFactory.decodeFile(imgFile.absolutePath)
                    if (bmp != null) {
                        thumb.setImageBitmap(bmp)
                        thumb.scaleType = ImageView.ScaleType.CENTER_CROP
                    }
                } catch (_: Exception) { }
            }
            frame.addView(thumb, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            card.addView(frame)

            val col = UI.col(ctx, true)
            col.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
            UI.margin(col, 12, 0, 8, 0, ctx)

            val nm = TextView(ctx)
            nm.text = p.name
            nm.setTextColor(UI.FG)
            nm.textSize = 15f
            nm.typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            nm.maxLines = 1
            col.addView(nm)

            val meta = TextView(ctx)
            meta.text = "${p.aspect.code}  \u00b7  ${p.layers.size} layer" +
                (if (p.layers.size == 1) "" else "s") + "  \u00b7  " + UI.fmtTime(p.durationMs())
            meta.setTextColor(UI.FG2)
            meta.textSize = 11.5f
            UI.margin(meta, 0, 2, 0, 0, ctx)
            col.addView(meta)

            card.addView(col)

            // actions
            val del = UI.chip(ctx, "\u2715")
            del.setTextColor(UI.DANGER)
            del.setOnClickListener {
                AlertDialog.Builder(ctx)
                    .setTitle("Delete project")
                    .setMessage("\"${p.name}\" and its project media will be deleted from this device.")
                    .setPositiveButton("Delete") { _, _ -> store.delete(p.id); refresh() }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
            val actionCol = UI.col(ctx, true)
            actionCol.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT)
            actionCol.gravity = Gravity.CENTER_VERTICAL or Gravity.END
            val dup = UI.chip(ctx, "Copy")
            dup.setOnClickListener {
                store.duplicate(p.id)
                refresh()
            }
            actionCol.addView(dup)
            UI.margin(del, 0, 6, 0, 0, ctx)
            actionCol.addView(del)
            card.addView(actionCol)
            return card
        }
    }
}
