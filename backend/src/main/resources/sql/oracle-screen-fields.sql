SELECT DISTINCT
    de.sqlname_c AS TABLE_NAME,
    da.sqlname_c AS FIELD_NAME
FROM ${schema}.script_definition sd
JOIN ${schema}.dict_attribute da ON da.dict_id = sd.attribute_dict_id
LEFT JOIN ${schema}.dict_entity de ON de.dict_id = da.entity_dict_id
WHERE sd.object_id IN (
    SELECT dict_id FROM ${schema}.dict_screen WHERE name = ?
)
ORDER BY de.sqlname_c, da.sqlname_c
