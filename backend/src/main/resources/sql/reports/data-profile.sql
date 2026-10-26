SELECT dp.code AS "DATA_PROFILE", dsp.code AS "DATA_SECU_PROFILE",
       dpc.auth_update_f AS "AUTH_UPDATE", dpc.auth_delete_f AS "AUTH_DELETE"
FROM ${data_profile} dp
JOIN ${data_prof_compo} dpc ON dp.id=dpc.data_profile_id
JOIN ${data_secu_prof} dsp ON dsp.id=dpc.data_secu_prof_id
WHERE dp.code=?
ORDER BY dp.code, dsp.code
