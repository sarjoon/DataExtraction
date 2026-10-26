SELECT USER_CODE, ACCESS_SOURCE, CHANNEL_CODE, CHANNEL_PROFILE, PROFILE_TYPE, PROFILE_CODE
FROM (
    SELECT au.code AS "USER_CODE", 'DIRECT_USER' AS "ACCESS_SOURCE", 'GUI' AS "CHANNEL_CODE",
           acp.code AS "CHANNEL_PROFILE", 'FUNCTION_SECURITY' AS "PROFILE_TYPE", p.code AS "PROFILE_CODE"
    FROM ${appl_user} au
    LEFT JOIN ${appl_channel_profile} acp ON acp.id=au.channel_profile_id
    LEFT JOIN ${func_secu_prof} p ON p.id=au.func_secu_prof_id
    UNION ALL
    SELECT au.code AS "USER_CODE", 'DIRECT_USER' AS "ACCESS_SOURCE", 'GUI' AS "CHANNEL_CODE",
           acp.code AS "CHANNEL_PROFILE", 'FORMAT' AS "PROFILE_TYPE", p.code AS "PROFILE_CODE"
    FROM ${appl_user} au
    LEFT JOIN ${appl_channel_profile} acp ON acp.id=au.channel_profile_id
    LEFT JOIN ${format_profile} p ON p.id=au.format_profile_id
    UNION ALL
    SELECT au.code AS "USER_CODE", 'DIRECT_USER' AS "ACCESS_SOURCE", 'GUI' AS "CHANNEL_CODE",
           acp.code AS "CHANNEL_PROFILE", 'REPORT' AS "PROFILE_TYPE", p.code AS "PROFILE_CODE"
    FROM ${appl_user} au
    LEFT JOIN ${appl_channel_profile} acp ON acp.id=au.channel_profile_id
    LEFT JOIN ${report_profile} p ON p.id=au.report_profile_id
    UNION ALL
    SELECT au.code AS "USER_CODE", 'DIRECT_USER' AS "ACCESS_SOURCE", 'GUI' AS "CHANNEL_CODE",
           acp.code AS "CHANNEL_PROFILE", 'SCREEN' AS "PROFILE_TYPE", p.code AS "PROFILE_CODE"
    FROM ${appl_user} au
    LEFT JOIN ${appl_channel_profile} acp ON acp.id=au.channel_profile_id
    LEFT JOIN ${screen_profile} p ON p.id=au.screen_profile_id
    UNION ALL
    SELECT au.code AS "USER_CODE", 'DIRECT_USER' AS "ACCESS_SOURCE", 'GUI' AS "CHANNEL_CODE",
           acp.code AS "CHANNEL_PROFILE", 'MENU' AS "PROFILE_TYPE", p.code AS "PROFILE_CODE"
    FROM ${appl_user} au
    LEFT JOIN ${appl_channel_profile} acp ON acp.id=au.channel_profile_id
    LEFT JOIN ${menu_profile} p ON p.id=au.menu_profile_id
    UNION ALL
    SELECT au.code AS "USER_CODE", 'DIRECT_USER' AS "ACCESS_SOURCE", 'GUI' AS "CHANNEL_CODE",
           acp.code AS "CHANNEL_PROFILE", 'DATA' AS "PROFILE_TYPE", p.code AS "PROFILE_CODE"
    FROM ${appl_user} au
    LEFT JOIN ${appl_channel_profile} acp ON acp.id=au.channel_profile_id
    LEFT JOIN ${data_profile} p ON p.id=au.data_profile_id
    UNION ALL
    SELECT au.code AS "USER_CODE", 'DIRECT_USER' AS "ACCESS_SOURCE", 'GUI' AS "CHANNEL_CODE",
           acp.code AS "CHANNEL_PROFILE", 'EXTERNAL_SERVICE' AS "PROFILE_TYPE", p.code AS "PROFILE_CODE"
    FROM ${appl_user} au
    LEFT JOIN ${appl_channel_profile} acp ON acp.id=au.channel_profile_id
    LEFT JOIN ${ext_service_profile} p ON p.id=au.ext_service_prof_id
    UNION ALL
    SELECT au.code AS "USER_CODE", 'CHANNEL_PROFILE' AS "ACCESS_SOURCE", ac.code AS "CHANNEL_CODE",
           acp.code AS "CHANNEL_PROFILE", 'FUNCTION_SECURITY' AS "PROFILE_TYPE", p.code AS "PROFILE_CODE"
    FROM ${appl_user} au
    JOIN ${appl_channel_profile} acp ON acp.id=au.channel_profile_id
    JOIN ${appl_channel_prof_compo} acpc ON acpc.appl_channel_profile_id=acp.id
    LEFT JOIN ${appl_channel} ac ON ac.id=acpc.appl_channel_id
    LEFT JOIN ${func_secu_prof} p ON p.id=acpc.func_secu_prof_id
    UNION ALL
    SELECT au.code AS "USER_CODE", 'CHANNEL_PROFILE' AS "ACCESS_SOURCE", ac.code AS "CHANNEL_CODE",
           acp.code AS "CHANNEL_PROFILE", 'SCREEN' AS "PROFILE_TYPE", p.code AS "PROFILE_CODE"
    FROM ${appl_user} au
    JOIN ${appl_channel_profile} acp ON acp.id=au.channel_profile_id
    JOIN ${appl_channel_prof_compo} acpc ON acpc.appl_channel_profile_id=acp.id
    LEFT JOIN ${appl_channel} ac ON ac.id=acpc.appl_channel_id
    LEFT JOIN ${screen_profile} p ON p.id=acpc.screen_profile_id
    UNION ALL
    SELECT au.code AS "USER_CODE", 'CHANNEL_PROFILE' AS "ACCESS_SOURCE", ac.code AS "CHANNEL_CODE",
           acp.code AS "CHANNEL_PROFILE", 'FORMAT' AS "PROFILE_TYPE", p.code AS "PROFILE_CODE"
    FROM ${appl_user} au
    JOIN ${appl_channel_profile} acp ON acp.id=au.channel_profile_id
    JOIN ${appl_channel_prof_compo} acpc ON acpc.appl_channel_profile_id=acp.id
    LEFT JOIN ${appl_channel} ac ON ac.id=acpc.appl_channel_id
    LEFT JOIN ${format_profile} p ON p.id=acpc.format_profile_id
    UNION ALL
    SELECT au.code AS "USER_CODE", 'CHANNEL_PROFILE' AS "ACCESS_SOURCE", ac.code AS "CHANNEL_CODE",
           acp.code AS "CHANNEL_PROFILE", 'REPORT' AS "PROFILE_TYPE", p.code AS "PROFILE_CODE"
    FROM ${appl_user} au
    JOIN ${appl_channel_profile} acp ON acp.id=au.channel_profile_id
    JOIN ${appl_channel_prof_compo} acpc ON acpc.appl_channel_profile_id=acp.id
    LEFT JOIN ${appl_channel} ac ON ac.id=acpc.appl_channel_id
    LEFT JOIN ${report_profile} p ON p.id=acpc.report_profile_id
    UNION ALL
    SELECT au.code AS "USER_CODE", 'CHANNEL_PROFILE' AS "ACCESS_SOURCE", ac.code AS "CHANNEL_CODE",
           acp.code AS "CHANNEL_PROFILE", 'MENU' AS "PROFILE_TYPE", p.code AS "PROFILE_CODE"
    FROM ${appl_user} au
    JOIN ${appl_channel_profile} acp ON acp.id=au.channel_profile_id
    JOIN ${appl_channel_prof_compo} acpc ON acpc.appl_channel_profile_id=acp.id
    LEFT JOIN ${appl_channel} ac ON ac.id=acpc.appl_channel_id
    LEFT JOIN ${menu_profile} p ON p.id=acpc.menu_profile_id
    UNION ALL
    SELECT au.code AS "USER_CODE", 'CHANNEL_PROFILE' AS "ACCESS_SOURCE", ac.code AS "CHANNEL_CODE",
           acp.code AS "CHANNEL_PROFILE", 'EXTERNAL_SERVICE' AS "PROFILE_TYPE", p.code AS "PROFILE_CODE"
    FROM ${appl_user} au
    JOIN ${appl_channel_profile} acp ON acp.id=au.channel_profile_id
    JOIN ${appl_channel_prof_compo} acpc ON acpc.appl_channel_profile_id=acp.id
    LEFT JOIN ${appl_channel} ac ON ac.id=acpc.appl_channel_id
    LEFT JOIN ${ext_service_profile} p ON p.id=acpc.ext_service_prof_id
) profiles
WHERE PROFILE_CODE IS NOT NULL AND USER_CODE=?
ORDER BY USER_CODE, ACCESS_SOURCE, CHANNEL_CODE, PROFILE_TYPE, PROFILE_CODE
