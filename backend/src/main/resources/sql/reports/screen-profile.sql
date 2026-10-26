SELECT sp.code AS "SCREEN_PROFILE", ds.name AS "SCREEN_NAME", de.sqlname_c AS "ENTITY",
       da1.sqlname_c AS "NATURE_ATTRIB", da2.sqlname_c AS "TYPE_ATTRIB",
       da3.sqlname_c AS "SUBTYPE_ATTRIB", t1.code AS "TYPE_CODE", t2.code AS "SUBTYPE_CODE",
       df.name AS "FUNCTION_CODE", r.code AS "REPORT_CODE"
FROM ${screen_prof_compo} spc
JOIN ${screen_profile} sp ON sp.id=spc.screen_profile_id
JOIN ${dict_screen} ds ON ds.dict_id=spc.screen_dict_id
LEFT JOIN ${dict_entity} de ON de.dict_id=spc.entity_dict_id
LEFT JOIN ${dict_attribute} da1 ON da1.dict_id=spc.nat_attrib_dict_id
LEFT JOIN ${dict_attribute} da2 ON da2.dict_id=spc.type_attrib_dict_id
LEFT JOIN ${dict_attribute} da3 ON da3.dict_id=spc.subtype_attrib_dict_id
LEFT JOIN ${type} t1 ON t1.id=spc.type_id
LEFT JOIN ${type} t2 ON t2.id=spc.subtype_id
LEFT JOIN ${dict_function} df ON df.dict_id=spc.function_dict_id
LEFT JOIN ${report} r ON r.id=spc.report_id
WHERE sp.code=?
ORDER BY sp.code, ds.name, de.sqlname_c, t1.code, t2.code, df.name, r.code
