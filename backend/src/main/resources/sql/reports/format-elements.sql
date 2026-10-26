SELECT f.code AS "FORMAT_CODE", fe.sqlname_c AS "ELEMENT_CODE",
       fe.name AS "ELEMENT_NAME", fe.denom AS "ELEMENT_DENOM",
       dd.sqlname_c AS "ELEMENT_DATATYPE", fe.rank_n AS "ELEMENT_RANK",
       fe.display_column_n AS "DISPLAY_COLUMN", fe.display_format_c AS "DISPLAY_FORMAT",
       fe.fixed_f AS "FIXED_COLUMN", fe.cons_f AS "CONSOLIDATION",
       fe.sub_total_f AS "SUB_TOTAL", fe.totaliser_c AS "TOTALISER",
       fe.col_width_n AS "COLUMN_WIDTH"
FROM ${format_element} fe
JOIN ${format} f ON fe.format_id=f.id
JOIN ${dict_datatype} dd ON fe.datatype_dict_id=dd.dict_id
WHERE f.code=?
ORDER BY f.code, fe.rank_n, fe.sqlname_c
