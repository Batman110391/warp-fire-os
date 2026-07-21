package com.batman110391.warpfiretv

import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.ListView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.batman110391.warpfiretv.warp.WarpConfigStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Picks which apps are routed through WARP.
 *
 * Selecting nothing means "every app", which is the sane default: an empty allowlist in Android
 * would instead mean "no app at all".
 */
class AppPickerActivity : ComponentActivity() {

    private lateinit var store: WarpConfigStore
    private lateinit var listView: ListView
    private lateinit var emptyView: TextView
    private lateinit var summaryView: TextView

    private val selected = linkedSetOf<String>()
    private var apps: List<AppEntry> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_app_picker)

        store = WarpConfigStore(this)
        listView = findViewById(R.id.app_list)
        emptyView = findViewById(R.id.app_list_empty)
        summaryView = findViewById(R.id.app_summary)

        selected += store.tunnelSettings.includedApps
        renderSummary()

        lifecycleScope.launch {
            apps = withContext(Dispatchers.IO) { loadLaunchableApps() }
            val adapter = AppAdapter()
            listView.adapter = adapter
            listView.setOnItemClickListener { _, _, position, _ ->
                val entry = apps[position]
                if (!selected.remove(entry.packageName)) selected += entry.packageName
                persist()
                renderSummary()
                adapter.notifyDataSetChanged()
            }
            emptyView.visibility = if (apps.isEmpty()) View.VISIBLE else View.GONE
            listView.requestFocus()
        }
    }

    private fun persist() {
        store.tunnelSettings = store.tunnelSettings.copy(includedApps = selected.toSet())
    }

    private fun renderSummary() {
        summaryView.text = if (selected.isEmpty()) {
            getString(R.string.picker_summary_all)
        } else {
            resources.getQuantityString(R.plurals.picker_summary_count, selected.size, selected.size)
        }
    }

    /**
     * Only apps the user can actually launch, on either a phone-style or a TV launcher. Listing
     * every installed package would bury Kodi under hundreds of Amazon system components.
     */
    private fun loadLaunchableApps(): List<AppEntry> {
        val packageManager = packageManager
        val seen = linkedMapOf<String, AppEntry>()
        for (category in listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)) {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(category)
            val resolved: List<ResolveInfo> = runCatching {
                packageManager.queryIntentActivities(intent, 0)
            }.getOrDefault(emptyList())
            for (info in resolved) {
                val packageName = info.activityInfo?.packageName ?: continue
                if (packageName == this.packageName || packageName in seen) continue
                seen[packageName] = AppEntry(
                    packageName = packageName,
                    label = info.loadLabel(packageManager).toString(),
                    icon = runCatching { info.loadIcon(packageManager) }.getOrNull(),
                )
            }
        }
        return seen.values.sortedBy { it.label.lowercase() }
    }

    private data class AppEntry(val packageName: String, val label: String, val icon: Drawable?)

    private inner class AppAdapter : BaseAdapter() {
        override fun getCount() = apps.size
        override fun getItem(position: Int) = apps[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: LayoutInflater.from(parent.context)
                .inflate(R.layout.item_app, parent, false)
            val entry = apps[position]
            view.findViewById<TextView>(R.id.app_label).text = entry.label
            view.findViewById<ImageView>(R.id.app_icon).setImageDrawable(entry.icon)
            view.findViewById<CheckBox>(R.id.app_checked).isChecked = entry.packageName in selected
            return view
        }
    }
}
