SELECT DISTINCT da.sqlname_c AS FIELD_NAME
FROM ${schema}.script_definition sd
JOIN ${schema}.dict_attribute da ON da.dict_id = sd.attribute_dict_id
WHERE sd.object_id IN (
    SELECT dict_id FROM ${schema}.dict_screen WHERE name = ?
)
ORDER BY da.sqlname_c
