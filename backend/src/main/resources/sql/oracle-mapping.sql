WITH selected_table AS (
    SELECT ? AS owner, ? AS table_name
    FROM dual
),
column_keys AS (
    SELECT
        acc.owner,
        acc.table_name,
        acc.column_name,
        MAX(CASE WHEN ac.constraint_type = 'P'
                 THEN 'PK' END) AS PK_FLAG,
        MAX(CASE WHEN ac.constraint_type = 'R'
                 THEN 'FK' END) AS FK_FLAG
    FROM selected_table s
    JOIN all_constraints ac
      ON ac.owner = s.owner
     AND ac.table_name = s.table_name
    JOIN all_cons_columns acc
      ON acc.owner = ac.owner
     AND acc.table_name = ac.table_name
     AND acc.constraint_name = ac.constraint_name
    WHERE ac.constraint_type IN ('P', 'R')
    GROUP BY acc.owner, acc.table_name, acc.column_name
)
SELECT
    c.column_id AS COLUMN_ID,
    c.column_name AS COLUMN_NAME,
    c.data_type AS DATA_TYPE,
    CASE
        WHEN c.data_type IN ('CLOB', 'BLOB') THEN 3000
        WHEN c.data_type = 'TIMESTAMP(6)' THEN 19
        WHEN c.data_type = 'DATE' THEN 10
        ELSE c.data_length
    END AS DATA_LENGTH,
    CASE WHEN c.char_used IS NOT NULL
         THEN c.char_length END AS CHAR_LENGTH,
    c.data_precision AS DATA_PRECISION,
    c.data_scale AS DATA_SCALE,
    c.nullable AS IS_NULLABLE,
    da.name AS FIELD_DESCRIPTION,
    CAST(NULL AS VARCHAR2(4000)) AS DEFAULT_VALUE,
    k.PK_FLAG,
    k.FK_FLAG,
    
    da.sqlname_c AS SOURCE_DATA_TYPE,
    CASE
        WHEN c.data_type IN ('CLOB', 'BLOB') THEN 3000
    
        WHEN c.data_type = 'NUMBER'
             THEN TO_NUMBER(REGEXP_SUBSTR(dd.EQUIV_TYPE_ORA_C, '[0-9]+'))
    
        WHEN c.data_type = 'TIMESTAMP(6)' THEN 19
        WHEN c.data_type = 'DATE' THEN 10
        ELSE da.MAX_DB_LEN_N
    END AS SOURCE_LENGTH,
    CASE
        WHEN c.data_type = 'NUMBER' THEN
            NVL(
                TO_NUMBER(
                    REGEXP_SUBSTR(
                        dd.EQUIV_TYPE_ORA_C,
                        ',([0-9]+)',
                        1,
                        1,
                        NULL,
                        1
                    )
                ),
                0
            )
        ELSE 0
    END AS SOURCE_DECIMAL
    
FROM selected_table s
JOIN all_tab_columns c
  ON c.owner = s.owner
 AND c.table_name = s.table_name

LEFT JOIN all_col_comments cc
  ON cc.owner = c.owner
 AND cc.table_name = c.table_name
 AND cc.column_name = c.column_name

LEFT JOIN column_keys k
  ON k.owner = c.owner
 AND k.table_name = c.table_name
 AND k.column_name = c.column_name

LEFT JOIN AAAMAINDB.dict_entity de
  ON UPPER(de.sqlname_c) = c.table_name

LEFT JOIN AAAMAINDB.dict_attribute da
  ON da.entity_dict_id = de.dict_id
 AND UPPER(da.sqlname_c) = c.column_name

LEFT JOIN AAAMAINDB.dict_datatype dd
  ON dd.dict_id = da.datatype_dict_id

ORDER BY c.column_id
