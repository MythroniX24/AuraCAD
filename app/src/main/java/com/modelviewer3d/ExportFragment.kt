package com.modelviewer3d

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.*
import android.widget.*
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

/**
 * Export & Share bottom sheet.
 * Formats: OBJ, STL
 * Actions: Save to device, Share (system chooser), Direct app (WhatsApp, Telegram, Email, Drive)
 */
class ExportFragment : BottomSheetDialogFragment() {

    private var selectedFormat = "obj"

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val ctx = requireContext()
        val scroll = ScrollView(ctx).apply { setBackgroundColor(0x00000000) }
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, 48)
            setBackgroundResource(R.drawable.bg_bottom_sheet)
        }
        scroll.addView(root)

        // ── Handle ───────────────────────────────────────────────────────────
        root.addView(LinearLayout(ctx).apply {
            gravity = android.view.Gravity.CENTER_HORIZONTAL
            setPadding(0, 14, 0, 0)
            addView(View(ctx).apply {
                setBackgroundColor(Color.parseColor("#607286"))
                layoutParams = LinearLayout.LayoutParams(48, 4)
            })
        })

        // ── Title ────────────────────────────────────────────────────────────
        root.addView(TextView(ctx).apply {
            text = "↑  Export & Share"
            textSize = 16f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Color.WHITE)
            setPadding(20, 14, 20, 4)
        })
        root.addView(TextView(ctx).apply {
            text = "Choose format and destination"
            textSize = 11f
            setTextColor(Color.parseColor("#74869A"))
            setPadding(20, 0, 20, 12)
        })

        root.addView(divider(ctx))

        // ── Format selector — compact single row that EXPANDS on tap ────────
        root.addView(sectionLabel(ctx, "FORMAT"))

        val fmtWrap = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(16, 8, 16, 12)
            visibility = View.GONE
        }
        val tvFmtValue = TextView(ctx).apply {
            text = "Wavefront OBJ · universal"; textSize = 11f
            setTextColor(Color.parseColor("#62E6FF"))
        }
        val tvFmtChevron = TextView(ctx).apply {
            text = "▾"; textSize = 16f; setTextColor(Color.parseColor("#74869A"))
            setPadding(8, 0, 0, 0)
        }
        val fmtHeader = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            background = ctx.getDrawable(R.drawable.bg_card_dark)
            setPadding(16, 14, 16, 14)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(16, 0, 16, 0) }
            setOnClickListener {
                val show = fmtWrap.visibility != View.VISIBLE
                fmtWrap.visibility = if (show) View.VISIBLE else View.GONE
                tvFmtChevron.text = if (show) "▴" else "▾"
            }
        }
        fmtHeader.addView(TextView(ctx).apply {
            text = "📦"; textSize = 16f
            layoutParams = LinearLayout.LayoutParams(36, LinearLayout.LayoutParams.WRAP_CONTENT)
        })
        fmtHeader.addView(TextView(ctx).apply {
            text = "Format"; textSize = 13f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        fmtHeader.addView(tvFmtValue)
        fmtHeader.addView(tvFmtChevron)
        root.addView(fmtHeader)

        val btnObj = makeFormatBtn(ctx, "OBJ", "Wavefront OBJ\nText · Universal", "#62E6FF", true)
        val btnStl = makeFormatBtn(ctx, "STL", "Stereolithography\nBinary · 3D Printing", "#A8B6C7", false)
        val btnPly = makeFormatBtn(ctx, "PLY", "Stanford PLY\nASCII · Meshes + Clouds", "#A8B6C7", false)

        fun applyFmt(fmt: String) {
            selectedFormat = fmt
            tintFormatBtn(btnObj, if (fmt == "obj") "#62E6FF" else "#A8B6C7", fmt == "obj")
            tintFormatBtn(btnStl, if (fmt == "stl") "#62E6FF" else "#A8B6C7", fmt == "stl")
            tintFormatBtn(btnPly, if (fmt == "ply") "#62E6FF" else "#A8B6C7", fmt == "ply")
            tvFmtValue.text = when (fmt) {
                "obj" -> "Wavefront OBJ · universal"
                "stl" -> "STL binary · 3D printing"
                else  -> "PLY · meshes & point clouds"
            }
        }
        btnObj.setOnClickListener { applyFmt("obj") }
        btnStl.setOnClickListener { applyFmt("stl") }
        btnPly.setOnClickListener { applyFmt("ply") }

        fmtWrap.addView(btnObj)
        fmtWrap.addView(View(ctx).apply { layoutParams = LinearLayout.LayoutParams(10, 1) })
        fmtWrap.addView(btnStl)
        fmtWrap.addView(View(ctx).apply { layoutParams = LinearLayout.LayoutParams(10, 1) })
        fmtWrap.addView(btnPly)
        root.addView(fmtWrap)

        root.addView(divider(ctx))

        // ── Save to Device ───────────────────────────────────────────────────
        root.addView(sectionLabel(ctx, "SAVE TO DEVICE"))
        root.addView(makeBigActionBtn(ctx, "💾", "Save to Downloads", "Saves in Downloads/AuraCAD", "#1A3D50", "#62E6FF") {
            (activity as? MainActivity)?.exportModel(selectedFormat, share = false)
            dismiss()
        })

        root.addView(View(ctx).apply { layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 8) })
        root.addView(divider(ctx))

        // ── Share via Apps ───────────────────────────────────────────────────
        root.addView(sectionLabel(ctx, "SHARE VIA"))

        // System share (all apps)
        root.addView(makeBigActionBtn(ctx, "↗", "Share via Any App", "Opens system share sheet", "#1A3D50", "#62E6FF") {
            (activity as? MainActivity)?.exportModel(selectedFormat, share = true)
            dismiss()
        })

        root.addView(View(ctx).apply { layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 8) })

        // App grid row
        val appRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(16, 4, 16, 12)
        }

        val shareApps = listOf(
            Triple("💬", "WhatsApp",  "com.whatsapp"),
            Triple("✈️", "Telegram",  "org.telegram.messenger"),
            Triple("📧", "Email",     null),
            Triple("☁️", "Drive",     "com.google.android.apps.docs")
        )
        for ((emoji, label, pkg) in shareApps) {
            appRow.addView(makeAppBtn(ctx, emoji, label) {
                if (pkg != null && !isAppInstalled(pkg)) {
                    Toast.makeText(ctx, "$label not installed", Toast.LENGTH_SHORT).show()
                } else {
                    (activity as? MainActivity)?.exportModel(selectedFormat, share = true, shareApp = pkg)
                    dismiss()
                }
            })
        }
        root.addView(appRow)

        return scroll
    }

    private fun isAppInstalled(pkg: String): Boolean {
        return try {
            requireContext().packageManager.getPackageInfo(pkg, 0)
            true
        } catch (_: Exception) { false }
    }

    private fun makeFormatBtn(ctx: android.content.Context, title: String, subtitle: String, color: String, active: Boolean): LinearLayout {
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = ctx.getDrawable(if (active) R.drawable.bg_card_selected else R.drawable.bg_card_dark)
            setPadding(14, 12, 14, 12)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            tag = Pair(color, active)
        }
        card.addView(TextView(ctx).apply {
            text = title
            textSize = 16f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Color.parseColor(color))
        })
        card.addView(TextView(ctx).apply {
            text = subtitle
            textSize = 10f
            setTextColor(Color.parseColor("#74869A"))
            setPadding(0, 4, 0, 0)
        })
        return card
    }

    private fun tintFormatBtn(card: LinearLayout, color: String, active: Boolean) {
        card.background = card.context.getDrawable(if (active) R.drawable.bg_card_selected else R.drawable.bg_card_dark)
        (card.getChildAt(0) as? TextView)?.setTextColor(Color.parseColor(color))
    }

    private fun makeBigActionBtn(
        ctx: android.content.Context,
        emoji: String, title: String, sub: String,
        bgHex: String, accentHex: String,
        onClick: () -> Unit
    ): LinearLayout {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            background = ctx.getDrawable(R.drawable.bg_card_dark)
            setPadding(16, 14, 16, 14)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(16, 0, 16, 0) }
            setOnClickListener { onClick() }
        }
        row.addView(TextView(ctx).apply {
            text = emoji
            textSize = 20f
            layoutParams = LinearLayout.LayoutParams(44, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, 0, 12, 0)
            }
            gravity = android.view.Gravity.CENTER
        })
        val texts = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        texts.addView(TextView(ctx).apply {
            text = title; textSize = 13f; setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Color.parseColor(accentHex))
        })
        texts.addView(TextView(ctx).apply {
            text = sub; textSize = 10f; setTextColor(Color.parseColor("#74869A")); setPadding(0, 2, 0, 0)
        })
        row.addView(texts)
        row.addView(TextView(ctx).apply {
            text = "›"; textSize = 20f; setTextColor(Color.parseColor("#303050"))
        })
        return row
    }

    private fun makeAppBtn(ctx: android.content.Context, emoji: String, label: String, onClick: () -> Unit): LinearLayout {
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { onClick() }
        }
        col.addView(TextView(ctx).apply {
            text = emoji
            textSize = 26f
            gravity = android.view.Gravity.CENTER
            background = ctx.getDrawable(R.drawable.bg_card_dark)
            setPadding(16, 14, 16, 14)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { gravity = android.view.Gravity.CENTER }
        })
        col.addView(TextView(ctx).apply {
            text = label; textSize = 10f
            setTextColor(Color.parseColor("#808099"))
            gravity = android.view.Gravity.CENTER
            setPadding(0, 6, 0, 0)
        })
        return col
    }

    private fun sectionLabel(ctx: android.content.Context, text: String) = TextView(ctx).apply {
        this.text = text; textSize = 9f; letterSpacing = 0.14f
        setTextColor(Color.parseColor("#607286"))
        setPadding(20, 14, 20, 6)
    }

    private fun divider(ctx: android.content.Context) = View(ctx).apply {
        setBackgroundColor(Color.parseColor("#243445"))
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
    }

    companion object {
        const val TAG = "ExportSheet"
        fun newInstance() = ExportFragment()
    }
}
