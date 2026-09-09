from pathlib import Path

p = Path('app/src/main/res/layout/activity_reader.xml')
s = p.read_text(encoding='utf-8')

old_first = '''            <LinearLayout
                android:layout_width="match_parent"
                android:layout_height="46dp"
                android:gravity="center_vertical"
                android:orientation="horizontal">

                <TextView
                    android:layout_width="54dp"
                    android:layout_height="match_parent"
                    android:gravity="center_vertical"
                    android:text="字号"
                    android:textColor="#EEE9DD"
                    android:textSize="15sp" />

                <TextView
                    android:id="@+id/fontDecreaseButton"
                    android:layout_width="72dp"
                    android:layout_height="36dp"
                    android:gravity="center"
                    android:background="#4A4842"
                    android:text="A-"
                    android:textColor="#EEE9DD"
                    android:textSize="18sp" />

                <TextView
                    android:id="@+id/fontSizeLabel"
                    android:layout_width="56dp"
                    android:layout_height="match_parent"
                    android:gravity="center"
                    android:text="18"
                    android:textColor="#EEE9DD"
                    android:textSize="16sp" />

                <TextView
                    android:id="@+id/fontIncreaseButton"
                    android:layout_width="72dp"
                    android:layout_height="36dp"
                    android:gravity="center"
                    android:background="#4A4842"
                    android:text="A+"
                    android:textColor="#EEE9DD"
                    android:textSize="18sp" />

                <TextView
                    android:id="@+id/volumeKeyToggleButton"
                    android:layout_width="0dp"
                    android:layout_height="36dp"
                    android:layout_marginStart="14dp"
                    android:layout_weight="1"
                    android:gravity="center"
                    android:background="#4A4842"
                    android:text="音量键翻页"
                    android:textColor="#EEE9DD"
                    android:textSize="15sp" />
            </LinearLayout>'''

new_first = '''            <LinearLayout
                android:layout_width="match_parent"
                android:layout_height="46dp"
                android:gravity="center_vertical"
                android:orientation="horizontal">

                <TextView
                    android:layout_width="42dp"
                    android:layout_height="match_parent"
                    android:gravity="center_vertical"
                    android:text="字号"
                    android:textColor="#EEE9DD"
                    android:textSize="14sp" />

                <TextView
                    android:id="@+id/fontDecreaseButton"
                    android:layout_width="50dp"
                    android:layout_height="34dp"
                    android:gravity="center"
                    android:background="#4A4842"
                    android:text="A-"
                    android:textColor="#EEE9DD"
                    android:textSize="15sp" />

                <TextView
                    android:id="@+id/fontSizeLabel"
                    android:layout_width="38dp"
                    android:layout_height="match_parent"
                    android:gravity="center"
                    android:text="18"
                    android:textColor="#EEE9DD"
                    android:textSize="14sp" />

                <TextView
                    android:id="@+id/fontIncreaseButton"
                    android:layout_width="50dp"
                    android:layout_height="34dp"
                    android:gravity="center"
                    android:background="#4A4842"
                    android:text="A+"
                    android:textColor="#EEE9DD"
                    android:textSize="15sp" />

                <TextView
                    android:id="@+id/volumeKeyToggleButton"
                    android:layout_width="0dp"
                    android:layout_height="34dp"
                    android:layout_marginStart="8dp"
                    android:layout_weight="1"
                    android:gravity="center"
                    android:background="#4A4842"
                    android:text="音量键翻页"
                    android:textColor="#EEE9DD"
                    android:textSize="13sp" />

                <TextView
                    android:id="@+id/selectTextToggleButton"
                    android:layout_width="0dp"
                    android:layout_height="34dp"
                    android:layout_marginStart="6dp"
                    android:layout_weight="1"
                    android:gravity="center"
                    android:background="#4A4842"
                    android:text="选中文本"
                    android:textColor="#EEE9DD"
                    android:textSize="13sp" />
            </LinearLayout>'''

if old_first not in s:
    raise SystemExit('missing V767 first settings row')
s = s.replace(old_first, new_first, 1)

old_extra = '''
            <LinearLayout
                android:layout_width="match_parent"
                android:layout_height="46dp"
                android:gravity="center_vertical"
                android:orientation="horizontal">

                <TextView
                    android:layout_width="54dp"
                    android:layout_height="match_parent"
                    android:gravity="center_vertical"
                    android:text="文本"
                    android:textColor="#EEE9DD"
                    android:textSize="15sp" />

                <TextView
                    android:id="@+id/selectTextToggleButton"
                    android:layout_width="0dp"
                    android:layout_height="36dp"
                    android:layout_weight="1"
                    android:gravity="center"
                    android:background="#4A4842"
                    android:text="选中文本"
                    android:textColor="#EEE9DD"
                    android:textSize="15sp" />
            </LinearLayout>
'''
if old_extra not in s:
    raise SystemExit('missing separate V767 text selection row')
s = s.replace(old_extra, '\n', 1)

p.write_text(s, encoding='utf-8')
print('v767 settings row adjusted: smaller/left font controls; selection toggle to the right of volume-key toggle')
