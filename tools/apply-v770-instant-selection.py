from pathlib import Path


def replace_once(text: str, old: str, new: str, label: str, marker: str | None = None) -> str:
    if marker and marker in text:
        return text
    if old not in text:
        raise SystemExit(f'missing patch anchor: {label}')
    return text.replace(old, new, 1)

# Version
build = Path('app/build.gradle.kts')
s = build.read_text(encoding='utf-8')
s = s.replace('2098000769', '2098000770')
s = s.replace('?: "769"', '?: "770"')
build.write_text(s, encoding='utf-8')

# Vertical reader: apply the new selectable state directly to already-attached holders.
adapter = Path('app/src/main/java/com/simplereader/app/ui/VerticalPageAdapter.kt')
s = adapter.read_text(encoding='utf-8')
s = replace_once(
    s,
    '''    fun refresh() {\n        rendered.evictAll()\n        notifyDataSetChanged()\n    }\n\n    fun release() {''',
    '''    fun refresh() {\n        rendered.evictAll()\n        notifyDataSetChanged()\n    }\n\n    /**\n     * V770: the selection switch must affect the text that is already on screen in the same UI\n     * turn.  Do not wait for RecyclerView to schedule a later onBindViewHolder pass.\n     * Off-screen rows still receive the same state from onBindViewHolder when they become visible.\n     */\n    fun applyTextSelectionStateImmediately(recyclerView: RecyclerView) {\n        val enabled = activity.isTextSelectionEnabled()\n        for (index in 0 until recyclerView.childCount) {\n            val child = recyclerView.getChildAt(index)\n            val holder = recyclerView.getChildViewHolder(child) as? VerticalPageHolder ?: continue\n            val position = holder.bindingAdapterPosition\n            if (position !in pages.indices) continue\n            holder.textView.setTextIsSelectable(enabled)\n            holder.textView.isLongClickable = enabled\n            activity.bindSelectionActions(holder.textView, pages[position].startOffset)\n        }\n    }\n\n    fun release() {''',
    'instant vertical selection state',
    'fun applyTextSelectionStateImmediately(recyclerView: RecyclerView)'
)
adapter.write_text(s, encoding='utf-8')

# ReaderActivity: do not queue a full adapter refresh just to activate the switch.
reader = Path('app/src/main/java/com/simplereader/app/ui/ReaderActivity.kt')
s = reader.read_text(encoding='utf-8')
s = replace_once(
    s,
    '''        pagedReaderView.setTextSelectionEnabled(textSelectionEnabled)\n        verticalAdapter?.refresh()\n    }''',
    '''        pagedReaderView.setTextSelectionEnabled(textSelectionEnabled)\n        verticalRecyclerView?.let { recycler ->\n            verticalAdapter?.applyTextSelectionStateImmediately(recycler)\n        }\n    }''',
    'apply selection immediately to visible vertical pages',
    'verticalAdapter?.applyTextSelectionStateImmediately(recycler)'
)
reader.write_text(s, encoding='utf-8')

print('v770 instant selected-text activation patch applied')
