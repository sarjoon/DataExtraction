SELECT ds.name AS "SCREEN_NAME", de.sqlname_c AS "ENTITY", dp.name AS "PANEL",
       den.denom AS "PANEL_DENOM", da.sqlname_c AS "ATTRIBUTE", dpv.name AS "NATURE",
       dp.edit_e AS "EDITABLE", dp.mandatory_f AS "MANDATORY",
       dp2.name AS "PARENT_PANEL", den2.denom AS "PARENT_PANEL_DENOM"
FROM ${dict_screen} ds
JOIN ${dict_entity} de ON de.dict_id=ds.entity_dict_id
JOIN ${dict_panel} dp ON dp.screen_dict_id=ds.dict_id
LEFT JOIN ${dict_attribute} da ON da.dict_id=dp.attribute_dict_id
LEFT JOIN ${dict_panel} dp2 ON dp2.dict_id=dp.parent_panel_dict_id
LEFT JOIN ${dict_entity} de_panel ON de_panel.sqlname_c='dict_panel'
LEFT JOIN ${dict_attribute} da_nature
  ON da_nature.entity_dict_id=de_panel.dict_id AND da_nature.sqlname_c='nature_e'
JOIN ${dict_perm_value} dpv
  ON dpv.attribute_dict_id=da_nature.dict_id AND dpv.perm_val_nat_e=dp.nature_e
LEFT JOIN ${denomination} den ON dp.dict_id=den.object_id
LEFT JOIN ${denomination} den2 ON dp.parent_panel_dict_id=den2.object_id
WHERE ds.name=?
ORDER BY ds.name, de.sqlname_c, dp.name, da.sqlname_c, den.denom, den2.denom
