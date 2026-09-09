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
import com.rehman.ahmedreactionstudio.R
import com.rehman.ahmedreactionstudio.core.Aspect
import com.rehman.ahmedreactionstudio.core.Project
import com.rehman.ahmedreactionstudio.core.ProjectStore
import com.rehman.ahmedreactionstudio.editor.EditorActivity
import com.rehman.ahmedreactionstudio.editor.Ic
import com.rehman.ahmedreactionstudio.util.UI
import java.io.File

/**
 * Home — project browser, polished to match the studio's floating chrome.
 *
 * - Header: 48dp logo badge (gradient + glow) + title 18sp Bold + subtitle 12sp
 *   + diagnostics chip 36dp, all on BG.
 * - Hint: 11sp muted, 14dp top margin.
 * - List: cards 88dp, radius 16dp, BG2 + white 8% stroke, elevation low,
 *   thumb 64dp radius 10dp, name 15sp Bold, meta 11sp FG2, actions 32dp chips.
 * - Empty state: frosted card with icon tile + CTA.
 * - New project dialog: name field + aspect chips 40dp, selected = ACCENT.
 */
class HomeActivity : Activity() {

    private lateinit var store: ProjectStore
    private val projects = ArrayList<Project>()
    private lateinit var adapter: ProjectsAdapter
    private lateinit var listView: ListView
    private lateinit var emptyView: LinearLayout

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
        // newest first (updatedAt desc) — answers "where is my last project?"
        projects.sortByDescending { it.updatedAt }
        adapter.notifyDataSetChanged()
        updateEmpty()
    }

    private fun updateEmpty() {
        val empty = projects.isEmpty()
        emptyView.visibility = if (empty) View.VISIBLE else View.GONE
        listView.visibility = if (empty) View.GONE else View.VISIBLE
    }

    private fun buildUi() {
        val root = UI.col(this, true)
        root.setBackgroundColor(UI.BG)
        root.setPadding(UI.dp(this, 16), UI.dp(this, 22), UI.dp(this, 16), 0)

        // ---- header: logo badge + title + diag ----
        val header = LinearLayout(this)
        header.orientation = LinearLayout.HORIZONTAL
        header.gravity = Gravity.CENTER_VERTICAL
        header.setBackgroundColor(UI.BG)

        val logo = TextView(this)
        logo.text = "\u25B6"
        logo.setTextColor(Color.WHITE)
        logo.textSize = 20f
        logo.gravity = Gravity.CENTER
        logo.setPadding(UI.dp(this, 2), 0, 0, 0)
        val lg = GradientDrawable(GradientDrawable.Orientation.TL_BR,
            intArrayOf(Color.rgb(255, 145, 60), Color.rgb(238, 60, 28)))
        lg.cornerRadius = UI.dpf(this, 12f)
        lg.setStroke(UI.dp(this, 1), Color.argb(90, 255, 255, 255))
        logo.background = lg
        logo.elevation = UI.dpf(this, UI.ELEV_LOW)
        val lp = LinearLayout.LayoutParams(UI.dp(this, 48), UI.dp(this, 48))
        logo.layoutParams = lp
        header.addView(logo)

        val tt = LinearLayout(this)
        tt.orientation = LinearLayout.VERTICAL
        tt.setBackgroundColor(UI.BG)
        tt.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        val tlp = tt.layoutParams as LinearLayout.LayoutParams
        tlp.marginStart = UI.dp(this, 12)
        val name = UI.title(this, "Ahmed Reaction Studio")
        name.textSize = 18f
        name.letterSpacing = -0.01f
        tt.addView(name)
        val sub = UI.label(this, "Local-first · reaction & PiP editor", dim = true, size = 11.5f)
        sub.setTextColor(UI.FG2)
        sub.letterSpacing = 0.02f
        UI.margin(sub, 0, 2, 0, 0, this)
        tt.addView(sub)
        header.addView(tt)

        val diagBtn = TextView(this)
        diagBtn.text = "Diagnostics"
        diagBtn.gravity = Gravity.CENTER
        diagBtn.setTextColor(UI.FG2)
        diagBtn.textSize = 11.5f
        diagBtn.typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        diagBtn.setPadding(UI.dp(this, 12), 0, UI.dp(this, 12), 0)
        diagBtn.background = UI.bg(this, UI.BG3, 18f, Color.argb(70, 255, 255, 255))
        diagBtn.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, UI.dp(this, 36))
        diagBtn.minHeight = UI.dp(this, 36)
        diagBtn.contentDescription = "Open diagnostics"
        diagBtn.setCompoundDrawablesRelativeWithIntrinsicBounds(
            Ic.get(this, R.drawable.ic_info, UI.FG2), null, null, null)
        diagBtn.compoundDrawablePadding = UI.dp(this, 6)
        diagBtn.setOnClickListener {
            startActivity(Intent(this, DiagnosticsActivity::class.java))
        }
        header.addView(diagBtn)
        root.addView(header)

        val hint = UI.label(this,
            "Projects live on this device only. No accounts, no cloud.",
            dim = true, size = 11f)
        hint.setTextColor(UI.TEXT_MUTED)
        hint.letterSpacing = 0.01f
        UI.margin(hint, 0, 14, 0, 0, this)
        root.addView(hint)

        // ---- empty state ----
        emptyView = LinearLayout(this)
        emptyView.orientation = LinearLayout.VERTICAL
        emptyView.gravity = Gravity.CENTER_HORIZONTAL
        emptyView.setPadding(UI.dp(this, 24), UI.dp(this, 32), UI.dp(this, 24), UI.dp(this, 24))
        emptyView.background = UI.cardBg(this, UI.BG2, UI.R_XL, Color.argb(50, 255, 255, 255))
        emptyView.elevation = UI.dpf(this, UI.ELEV_LOW)
        val emptyLp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT)
        emptyLp.topMargin = UI.dp(this, 24)
        emptyView.layoutParams = emptyLp

        val emptyIconWrap = FrameLayout(this)
        emptyIconWrap.background = UI.bg(this, UI.BG3, 24f, Color.argb(60, 255, 255, 255))
        val emptyIcon = ImageView(this)
        emptyIcon.setImageDrawable(Ic.get(this, R.drawable.ic_layers, UI.ACCENT2))
        emptyIcon.setPadding(UI.dp(this, 16), UI.dp(this, 16), UI.dp(this, 16), UI.dp(this, 16))
        emptyIconWrap.addView(emptyIcon, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        emptyView.addView(emptyIconWrap, LinearLayout.LayoutParams(UI.dp(this, 72), UI.dp(this, 72)))

        val emptyTitle = UI.titleSmall(this, "No projects yet")
        emptyTitle.gravity = Gravity.CENTER
        UI.margin(emptyTitle, 0, 16, 0, 0, this)
        emptyView.addView(emptyTitle)

        val emptySub = UI.label(this, "Create your first reaction canvas — 16:9 for YouTube,\n9:16 for Shorts, 1:1 for square posts.", dim = true, size = 12f)
        emptySub.gravity = Gravity.CENTER
        emptySub.setTextColor(UI.FG2)
        UI.margin(emptySub, 0, 6, 0, 0, this)
        emptyView.addView(emptySub)

        val emptyCta = UI.chipAccent(this, "+ New project")
        emptyCta.textSize = 13f
        emptyCta.setPadding(UI.dp(this, 20), 0, UI.dp(this, 20), 0)
        emptyCta.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, UI.dp(this, 42))
        UI.margin(emptyCta, 0, 18, 0, 0, this)
        emptyCta.setOnClickListener { showNewDialog() }
        emptyView.addView(emptyCta)
        root.addView(emptyView)

        // ---- list ----
        listView = ListView(this)
        listView.divider = null
        listView.dividerHeight = 0
        listView.setPadding(0, UI.dp(this, 8), 0, UI.dp(this, 8))
        listView.clipToPadding = false
        listView.isVerticalScrollBarEnabled = false
        listView.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        adapter = ProjectsAdapter()
        listView.adapter = adapter
        listView.onItemClickListener = AdapterView.OnItemClickListener { _, _, pos, _ ->
            if (pos in projects.indices) openProject(projects[pos].id)
        }
        listView.onItemLongClickListener = AdapterView.OnItemLongClickListener { _, _, pos, _ ->
            projects.getOrNull(pos)?.let { showProjectMenu(it) }
            true
        }
        root.addView(listView)

        // ---- new project button (sticky bottom) ----
        val newBtn = TextView(this)
        newBtn.text = "+  New project"
        newBtn.gravity = Gravity.CENTER
        newBtn.setTextColor(UI.ACCENT_FG)
        newBtn.textSize = 14f
        newBtn.typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        newBtn.letterSpacing = 0.02f
        newBtn.setPadding(0, 0, 0, 0)
        newBtn.background = UI.bg(this, UI.ACCENT, UI.R_M, UI.ACCENT_BORDER)
        newBtn.elevation = UI.dpf(this, UI.ELEV_LOW)
        val btnLp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, UI.dp(this, 50))
        btnLp.topMargin = UI.dp(this, 12)
        btnLp.bottomMargin = UI.dp(this, 16)
        newBtn.layoutParams = btnLp
        newBtn.contentDescription = "Create a new project"
        newBtn.setOnClickListener { showNewDialog() }
        root.addView(newBtn)

        setContentView(root)
        updateEmpty()
    }

    private fun showProjectMenu(p: Project) {
        val labels = arrayOf("Open", "Rename project", "Duplicate", "Delete")
        AlertDialog.Builder(this)
            .setTitle(p.name)
            .setItems(labels) { _, which ->
                when (which) {
                    0 -> openProject(p.id)
                    1 -> renameProject(p)
                    2 -> { store.duplicate(p.id); refresh() }
                    3 -> confirmDelete(p)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun renameProject(p: Project) {
        val input = EditText(this)
        input.setText(p.name)
        input.setSelection(input.text.length)
        input.setTextColor(UI.FG)
        input.setHintTextColor(Color.argb(150, 255, 255, 255))
        input.background = UI.bg(this, UI.BG3, UI.R_M, Color.argb(70, 255, 255, 255))
        input.setPadding(UI.dp(this, 14), UI.dp(this, 12), UI.dp(this, 14), UI.dp(this, 12))
        val pad = LinearLayout(this)
        pad.setPadding(UI.dp(this, 22), UI.dp(this, 8), UI.dp(this, 22), 0)
        pad.addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT))
        AlertDialog.Builder(this)
            .setTitle("Rename project")
            .setView(pad)
            .setPositiveButton("Rename") { _, _ ->
                val nm = input.text.toString().trim()
                if (nm.isEmpty()) return@setPositiveButton
                val full = store.load(p.id) ?: return@setPositiveButton
                full.name = nm
                store.save(full)
                refresh()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmDelete(p: Project) {
        AlertDialog.Builder(this)
            .setTitle("Delete project")
            .setMessage("\"${p.name}\" and its media will be deleted from this device.")
            .setPositiveButton("Delete") { _, _ -> store.delete(p.id); refresh() }
            .setNegativeButton("Cancel", null)
            .show()
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
        nameInput.background = UI.bg(this, UI.BG3, UI.R_M, Color.argb(70, 255, 255, 255))
        nameInput.setPadding(UI.dp(this, 14), UI.dp(this, 12), UI.dp(this, 14), UI.dp(this, 12))
        holder.addView(nameInput, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT))

        val aspectLabel = UI.label(this, "Canvas aspect", dim = true, size = 11f)
        aspectLabel.setTextColor(UI.FG2)
        aspectLabel.letterSpacing = 0.06f
        UI.margin(aspectLabel, 0, 16, 0, 6, this)
        holder.addView(aspectLabel)

        val aspectRow = LinearLayout(this)
        aspectRow.orientation = LinearLayout.HORIZONTAL
        aspectRow.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        UI.margin(aspectRow, 0, 0, 0, 6, this)
        val chips = HashMap<Aspect, TextView>()
        for (a in Aspect.entries) {
            val c = TextView(this)
            c.text = a.code
            c.gravity = Gravity.CENTER
            c.textSize = 13f
            c.typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            c.setPadding(0, 0, 0, 0)
            c.minHeight = UI.dp(this, 40)
            c.layoutParams = LinearLayout.LayoutParams(0, UI.dp(this, 40), 1f).apply {
                marginEnd = UI.dp(this@HomeActivity, 8)
            }
            c.contentDescription = "Canvas aspect ratio ${a.code}"
            c.setOnClickListener {
                for ((_, v) in chips) v.isSelected = (v === c)
                refreshChips(chips)
            }
            chips[a] = c
            aspectRow.addView(c)
        }
        // remove last margin
        (chips.values.lastOrNull()?.layoutParams as? LinearLayout.LayoutParams)?.marginEnd = 0
        holder.addView(aspectRow)

        AlertDialog.Builder(this)
            .setTitle("New project")
            .setView(holder)
            .setPositiveButton("Create") { _, _ ->
                val name = nameInput.text.toString().trim().ifEmpty { "My Reaction" }
                val aspect = chips.entries.firstOrNull { it.value.isSelected }?.key ?: Aspect.R169
                val p = store.create(name, aspect)
                openProject(p.id)
            }
            .setNegativeButton("Cancel", null)
            .show()
        chips[Aspect.R169]?.isSelected = true
        refreshChips(chips)
    }

    private fun refreshChips(chips: HashMap<Aspect, TextView>) {
        for ((_, v) in chips) {
            if (v.isSelected) {
                v.background = UI.bg(this, UI.ACCENT, UI.R_M, Color.argb(140, 255, 220, 180))
                v.setTextColor(UI.ACCENT_FG)
                v.elevation = UI.dpf(this, 2f)
            } else {
                v.background = UI.bg(this, UI.BG3, UI.R_M, Color.argb(60, 255, 255, 255))
                v.setTextColor(UI.FG)
                v.elevation = 0f
            }
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
            card.gravity = Gravity.CENTER_VERTICAL
            card.setPadding(UI.dp(ctx, 12), UI.dp(ctx, 12), UI.dp(ctx, 12), UI.dp(ctx, 12))
            card.background = UI.cardBg(ctx, UI.BG2, UI.R_L, Color.argb(50, 255, 255, 255))
            card.elevation = UI.dpf(ctx, 2f)
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, UI.dp(ctx, 88))
            lp.setMargins(0, 0, 0, UI.dp(ctx, 10))
            card.layoutParams = lp

            // thumb 64dp
            val frame = FrameLayout(ctx)
            frame.layoutParams = LinearLayout.LayoutParams(UI.dp(ctx, 64), UI.dp(ctx, 64))
            frame.background = UI.bg(ctx, UI.BG3, 10f, Color.argb(40, 255, 255, 255))
            val thumb = ImageView(ctx)
            thumb.scaleType = ImageView.ScaleType.CENTER_CROP
            thumb.tag = null
            val imgFile = store.thumbFile(p.id)
            if (imgFile.exists()) {
                val path = imgFile.absolutePath
                thumb.tag = path
                Thread {
                    try {
                        val opts = BitmapFactory.Options().apply { inSampleSize = 4 }
                        val bmp = BitmapFactory.decodeFile(path, opts)
                        thumb.post {
                            if (thumb.tag == path && bmp != null) thumb.setImageBitmap(bmp)
                            else try { bmp?.recycle() } catch (_: Exception) { }
                        }
                    } catch (_: Exception) { }
                }.start()
            } else {
                // placeholder icon
                thumb.setImageDrawable(Ic.get(ctx, R.drawable.ic_image, UI.FG2))
                thumb.setPadding(UI.dp(ctx, 18), UI.dp(ctx, 18), UI.dp(ctx, 18), UI.dp(ctx, 18))
            }
            frame.addView(thumb, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            card.addView(frame)

            val col = LinearLayout(ctx)
            col.orientation = LinearLayout.VERTICAL
            col.setBackgroundColor(Color.TRANSPARENT)
            col.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            (col.layoutParams as LinearLayout.LayoutParams).marginStart = UI.dp(ctx, 12)
            (col.layoutParams as LinearLayout.LayoutParams).marginEnd = UI.dp(ctx, 8)

            val nm = TextView(ctx)
            nm.text = p.name
            nm.setTextColor(Color.WHITE)
            nm.textSize = 14.5f
            nm.typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            nm.maxLines = 1
            nm.ellipsize = android.text.TextUtils.TruncateAt.END
            nm.letterSpacing = -0.01f
            col.addView(nm)

            val meta = TextView(ctx)
            val layerLabel = if (p.layers.size == 1) "layer" else "layers"
            meta.text = "${p.aspect.code} · ${p.layers.size} $layerLabel · ${UI.fmtTime(p.durationMs())} · ${UI.relTime(p.updatedAt)}"
            meta.setTextColor(UI.FG2)
            meta.textSize = 11f
            meta.maxLines = 1
            meta.ellipsize = android.text.TextUtils.TruncateAt.END
            meta.letterSpacing = 0.01f
            val metaLp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT)
            metaLp.topMargin = UI.dp(ctx, 3)
            meta.layoutParams = metaLp
            col.addView(meta)

            card.addView(col)

            // actions column — 32dp chips, 44dp touch via padding
            val actionCol = LinearLayout(ctx)
            actionCol.orientation = LinearLayout.VERTICAL
            actionCol.setBackgroundColor(Color.TRANSPARENT)
            actionCol.gravity = Gravity.CENTER_VERTICAL or Gravity.END
            actionCol.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT)

            val dup = TextView(ctx)
            dup.text = "Copy"
            dup.gravity = Gravity.CENTER
            dup.setTextColor(UI.FG2)
            dup.textSize = 11f
            dup.typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            dup.setPadding(UI.dp(ctx, 10), 0, UI.dp(ctx, 10), 0)
            dup.background = UI.bg(ctx, UI.BG3, 16f, Color.argb(60, 255, 255, 255))
            dup.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, UI.dp(ctx, 32))
            dup.minHeight = UI.dp(ctx, 32)
            dup.contentDescription = "Duplicate project ${p.name}"
            dup.setOnClickListener {
                store.duplicate(p.id)
                refresh()
            }
            actionCol.addView(dup)

            val del = TextView(ctx)
            del.text = "Delete"
            del.gravity = Gravity.CENTER
            del.setTextColor(UI.DANGER)
            del.textSize = 10.5f
            del.typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            del.letterSpacing = 0.04f
            del.setPadding(UI.dp(ctx, 10), 0, UI.dp(ctx, 10), 0)
            del.background = UI.bg(ctx, Color.argb(36, 235, 90, 90), 14f, Color.argb(80, 235, 90, 90))
            del.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, UI.dp(ctx, 32)).apply {
                topMargin = UI.dp(ctx, 6)
            }
            del.minHeight = UI.dp(ctx, 32)
            del.minWidth = UI.dp(ctx, 56)
            del.contentDescription = "Delete project ${p.name}"
            del.setOnClickListener { confirmDelete(p) }
            actionCol.addView(del)

            card.addView(actionCol)
            return card
        }
    }
}
