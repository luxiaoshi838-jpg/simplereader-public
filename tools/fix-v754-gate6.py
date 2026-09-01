from pathlib import Path
p=Path('tools/v748-14-gates.sh')
s=p.read_text()
old='need "$T" \'CATALOG_RULE_VERSION = 111\' && need "$D" \'RULE_VERSION = 111\' || fail 6 "catalog rule 111"\npass 6 "catalog rule version 111"'
new='''T_RULE="$(grep -Eo 'CATALOG_RULE_VERSION = [0-9]+' "$T" | head -n1 | grep -Eo '[0-9]+')"
D_RULE="$(grep -Eo 'RULE_VERSION = [0-9]+' "$D" | head -n1 | grep -Eo '[0-9]+')"
[ -n "$T_RULE" ] && [ "$T_RULE" = "$D_RULE" ] && [ "$T_RULE" -ge 111 ] || fail 6 "catalog rule version mismatch/below v745 baseline"
pass 6 "catalog rule version $T_RULE, matched and >=111 baseline"'''
if old not in s:
    raise SystemExit('gate6 anchor missing')
p.write_text(s.replace(old,new,1))
