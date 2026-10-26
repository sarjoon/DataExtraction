SELECT fp.code AS "FORMAT_PROFILE", f.code AS "FORMAT", f.name AS "FORMAT_NAME",
       df.name AS "FUNCTION", dpv.name AS "NATURE", fpc.rank_n AS "RANK"
FROM ${format} f
JOIN ${format_prof_compo} fpc ON fpc.format_id=f.id
JOIN ${format_profile} fp ON fpc.format_profile_id=fp.id
JOIN ${dict_function} df ON df.dict_id=f.function_dict_id
JOIN ${dict_perm_value} dpv ON f.nature_e=dpv.perm_val_nat_e
JOIN ${dict_attribute} da ON dpv.attribute_dict_id=da.dict_id
WHERE da.dict_id=(
    SELECT dict_id FROM ${dict_attribute}
    WHERE sqlname_c='nature_e'
      AND entity_dict_id=(SELECT dict_id FROM ${dict_entity} WHERE sqlname_c='format')
)
AND fp.code=?
ORDER BY fp.code, df.name, dpv.name, fpc.rank_n, f.code
