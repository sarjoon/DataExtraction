SELECT fsp.code AS "FSP_CODE", df.name AS "FUNCTION", de.sqlname_c AS "ENTITY",
       t1.code AS "TYPE", t2.code AS "SUBTYPE",
       dpv1.name AS "MIN_STATUS", dpv2.name AS "MAX_STATUS",
       fspc.create_f AS "CREATE_FLAG", fspc.update_f AS "UPDATE_FLAG",
       fspc.delete_f AS "DELETE_FLAG", fspc.risk_view_f AS "RISK_VIEW_FLAG",
       fspc.real_time_f AS "REALTIME_FLAG", fspc.view_f AS "VIEW_FLAG",
       fspc.visible_f AS "VISIBLE_FLAG"
FROM ${func_secu_prof} fsp
JOIN ${func_secu_prof_compo} fspc ON fsp.id=fspc.func_secu_prof_id
LEFT JOIN ${dict_function} df ON df.dict_id=fspc.function_dict_id
LEFT JOIN ${dict_entity} de ON de.dict_id=fspc.entity_dict_id
LEFT JOIN ${type} t1 ON t1.id=fspc.type_id
LEFT JOIN ${type} t2 ON t2.id=fspc.subtype_id
LEFT JOIN ${dict_entity} de_op ON de_op.sqlname_c='operation'
LEFT JOIN ${dict_attribute} da_status
  ON da_status.entity_dict_id=de_op.dict_id AND da_status.sqlname_c='status_e'
LEFT JOIN ${dict_perm_value} dpv1
  ON dpv1.attribute_dict_id=da_status.dict_id AND dpv1.perm_val_nat_e=fspc.min_status_e
LEFT JOIN ${dict_perm_value} dpv2
  ON dpv2.attribute_dict_id=da_status.dict_id AND dpv2.perm_val_nat_e=fspc.max_status_e
WHERE fsp.code=?
ORDER BY fsp.code, df.name, de.sqlname_c, t1.code, t2.code, dpv1.name, dpv2.name
