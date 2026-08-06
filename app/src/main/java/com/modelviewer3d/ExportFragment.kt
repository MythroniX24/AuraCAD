package com.modelviewer3d

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

/**
 * Export — the single professional destination for every supported format.
 *
 * Shows the loaded model, a 2-column format picker (OBJ / STL / PLY / GLB /
 * 3DS / 3DM) with per-format descriptions, then two clear actions:
 *   • EXPORT & SAVE  → writes to Downloads/AuraCAD
 *   • SHARE          → writes + opens the system share sheet
 *
 * The heavy work (GL-thread native call + MediaStore write) runs inside
 * [MainActivity.exportModel] so all export logic stays in one place.
 */
class ExportFragment : BottomSheetDialogFragment() {

    companion object {
        const val TAG = "ExportFragment"
        fun newInstance() = ExportFragment()

        data class Format(
            val id: String,
            val name: String,
            val ext: String,
            val desc: String,
            val color: String
        )

        val FORMATS = listOf(
            Format("OBJ", "Wavefront OBJ", "obj", "Universal · textures & groups", "#4DD8FF"),
            Format("STL", "Stereolithography", "stl", "3D printing standard", "#FFC46B"),
            Format("PLY", "Polygon", "ply", "Scans · point clouds · color", "#A78BFA"),
            Format("GLB", "glTF Binary", "glb", "Web · AR / VR ready", "#4CAF82"),
            Format("3DS", "Autodesk 3DS", "3ds", "Legacy CAD scenes", "#FF7A72"),
            Format("3DM", "Rhino 3DM", "3dm", "NURBS · CAD native", "#FFD54F"),
        )
    }

    private var selectedId = "OBJ"
    private val formatCells = mutableMapOf<String, LinearLayout>()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?,
                              savedInstanceState: Bundle?): View {
        val ctx = requireContext()
        val scroll = ScrollView(ctx).apply {
            setBackgroundColor(0x00000000)
            isFillViewport = true
        }
        val root = UISheetKit.sheetRoot(ctx)
        scroll.addView(root)

        root.addView(UISheetKit.handle(ctx))
        root.addView(UISheetKit.titleRow(ctx, "Export Model", "6 FORMATS"))
        root.addView(UISheetKit.divider(ctx))

        // ── Source model card ─────────────────────────────────────────────────
        root.addView(UISheetKit.card(ctx, marginTopDp = 0).apply {
            addView(UISheetKit.cardTitle(ctx, "SOURCE MODEL", "#4DD8FF"))
            val act = requireActivity()
            val name = if (act is MainActivity) act.currentDisplayFileName.ifEmpty { "Untitled scene" }
                       else "Untitled scene"
            addView(TextView(ctx).apply {
                text = name
                textSize = 13f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.parseColor(UISheetKit.TEXT_MAIN))
                setSingleLine(true)
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
            addView(UISheetKit.subText(ctx,
                "Everything below exports with real-world millimetre units — " +
                "your model stays correctly sized in every format.",
                "#7A8BA3", 10f).apply {
                setPadding(0, UISheetKit.dp(ctx, 4), 0, 0)
            })
        })

        // ── Format picker ─────────────────────────────────────────────────────
        root.addView(UISheetKit.sectionLabel(ctx, "SELECT FORMAT"))
        FORMATS.chunked(2).forEach { row ->
            val rowLay = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(UISheetKit.dp(ctx, UISheetKit.PAD_SIDE), 0,
                               UISheetKit.dp(ctx, UISheetKit.PAD_SIDE), 0)
                }
            }
            row.forEach { fmt ->
                rowLay.addView(buildFormatCell(ctx, fmt))
            }
            root.addView(rowLay)
        }

        // ── Actions ───────────────────────────────────────────────────────────
        root.addView(UISheetKit.primaryButton(ctx, "EXPORT & SAVE").apply {
            setOnClickListener { runExport(share = false) }
        })
        root.addView(UISheetKit.secondaryButton(ctx, "SHARE EXPORT", "#4DD8FF", 48) {
            runExport(share = true)
        })
        root.addView(UISheetKit.infoText(ctx,
            "Files are saved to Downloads/AuraCAD and are safe to send over " +
            "WhatsApp, Telegram, Gmail & more.",
            "#5A6B85", 10f))

        return scroll
    }

    /** One selectable format cell (half-width). */
    private fun buildFormatCell(ctx: android.content.Context, fmt: Format): LinearLayout {
        val cell = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(UISheetKit.dp(ctx, 12), UISheetKit.dp(ctx, 12),
                       UISheetKit.dp(ctx, 12), UISheetKit.dp(ctx, 12))
            background = ctx.getDrawable(R.drawable.bg_card_dark)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(0, 0, UISheetKit.dp(ctx, 6), UISheetKit.dp(ctx, 10))
            }
            isClickable = true
            isFocusable = true
            setOnClickListener { selectFormat(fmt.id) }
        }

        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        // Extension pill
        row.addView(TextView(ctx).apply {
            text = fmt.ext.uppercase()
            textSize = 11f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#0B1320"))
            background = ctx.getDrawable(R.drawable.bg_pill)?.mutate()
            backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor(fmt.color))
            setPadding(UISheetKit.dp(ctx, 8), UISheetKit.dp(ctx, 4),
                       UISheetKit.dp(ctx, 8), UISheetKit.dp(ctx, 4))
        })
        // Selected check dot (hidden until chosen)
        row.addView(TextView(ctx).apply {
            text = "●"
            textSize = 8f
            setTextColor(Color.parseColor(UISheetKit.CYAN))
            visibility = View.GONE
            setPadding(UISheetKit.dp(ctx, 8), 0, 0, 0)
        }.also { cell.tag = it })
        cell.addView(row)

        cell.addView(TextView(ctx).apply {
            text = fmt.name
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor(UISheetKit.TEXT_MAIN))
            setPadding(0, UISheetKit.dp(ctx, 8), 0, 0)
        })
        cell.addView(TextView(ctx).apply {
            text = fmt.desc
            textSize = 9f
            setTextColor(Color.parseColor("#7A8BA3"))
            setPadding(0, UISheetKit.dp(ctx, 2), 0, 0)
            maxLines = 1
        })

        formatCells[fmt.id] = cell
        return cell
    }

    private fun selectFormat(id: String) {
        selectedId = id
        FORMATS.forEach { fmt ->
            val cell = formatCells[fmt.id] ?: return@forEach
            val dot = cell.tag as? TextView
            val selected = fmt.id == id
            cell.background = ctx?.getDrawable(if (selected) R.drawable.bg_card_selected
                                               else R.drawable.bg_card_dark)
            dot?.visibility = if (selected) View.VISIBLE else View.GONE
        }
    }

    private val ctx: android.content.Context?
        get() = context

    private fun runExport(share: Boolean) {
        val act = requireActivity()
        if (act is MainActivity) {
            dismiss()
            act.exportModel(selectedId, share)
        } else {
            dismiss()
        }
    }
}
