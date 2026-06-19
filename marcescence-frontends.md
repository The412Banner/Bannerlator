# ES-DE
- Thanks to xabbu33 for the pull request and tutorial.

Use the correct am start command:

```am start \
  -n com.winlator.star/com.winlator.star.XServerDisplayActivity \
  -e shortcut_path {file.path} \
  --activity-clear-task \
  --activity-clear-top
```


For ES-DE, add this to your `custom_systems`/`es_find_rules.xml`:
```<emulator name="WINLATOR-STAR">
    <rule type="androidpackage">
        <entry>com.winlator.star/.XServerDisplayActivity</entry>
    </rule>
</emulator> 
```

And in es_systems.xml:
```
<system>
    <name>windows</name>
    <fullname>Microsoft Windows</fullname>
    <path>%ROMPATH%/windows</path>
    <extension>.desktop .DESKTOP</extension>
    <command label="Winlator Star (Standalone)">%EMULATOR_WINLATOR-STAR% %ACTIVITY_CLEAR_TASK% %ACTIVITY_CLEAR_TOP% %EXTRA_shortcut_path%=%ROM%</command>
    <platform>windows</platform>
    <theme>windows</theme>
</system> 
```

Drop your exported .desktop shortcuts from Winlator into `ROMs/windows/` and they'll show up as games in ES-DE.
