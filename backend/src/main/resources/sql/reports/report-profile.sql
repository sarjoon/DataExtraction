SELECT rp.code AS "REPORT_PROFILE", r.code AS "REPORT_CODE", r.denom AS "REPORT_DENOM"
FROM ${report_prof_compo} rpc
JOIN ${report_profile} rp ON rp.id=rpc.report_profile_id
JOIN ${report} r ON r.id=rpc.report_id
WHERE rp.code=?
ORDER BY rp.code, r.code
