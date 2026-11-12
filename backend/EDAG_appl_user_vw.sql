-----------------------------------------------------------------------------
-- DDL for View 'AAAREPDB..EDAG_appl_user_vw'
-----------------------------------------------------------------------------
print '<<<<< CREATING View - "AAAREPDB..EDAG_appl_user_vw" >>>>>'
go 
use AAAREPDB
go
IF EXISTS (SELECT 1 FROM sysobjects o, sysusers u WHERE o.uid=u.uid AND o.name = 'EDAG_appl_user_vw'  AND o.type = 'V')
BEGIN
	drop view EDAG_appl_user_vw
END
go

IF (@@error != 0)
BEGIN
	PRINT 'Error CREATING View AAAREPDB..EDAG_appl_user_vw'
	SELECT syb_quit()
END
go

create view EDAG_appl_user_vw as 
select 
id = E.id,
code = E.code,
name = E.name,
denom = E.denom,
language_dict_id = E.language_dict_id,
func_secu_prof_id = E.func_secu_prof_id,
last_domain_id = E.last_domain_id,
format_profile_id = E.format_profile_id,
report_profile_id = E.report_profile_id,
screen_profile_id = E.screen_profile_id,
printer_profile_id = E.printer_profile_id,
active_f = E.active_f,
os_user_c = E.os_user_c,
security_admin_f = E.security_admin_f,
superuser_f = E.superuser_f,
audit_f = E.audit_f,
str_replace(str_replace(convert(varchar(20),E.validity_d,23),'T',' '),'.',null) as validity_d,
gui_user_f = E.gui_user_f,
max_running_gui_n = E.max_running_gui_n,
parent_user_id = E.parent_user_id,
data_profile_id = E.data_profile_id,
data_secu_prof_id = E.data_secu_prof_id,
wui_user_f = E.wui_user_f,
quick_search_prof_id = E.quick_search_prof_id,
manager_id = E.manager_id,
--gui_context_t = E.gui_context_t,
fund_data_prof_id = E.fund_data_prof_id,
third_id = E.third_id,
has_db_login_f = E.has_db_login_f,
system_user_f = E.system_user_f,
data_secu_prof2_id = E.data_secu_prof2_id,
tasc_user_f = E.tasc_user_f,
remoting_user_f = E.remoting_user_f,
batch_user_f = E.batch_user_f,
update_right_f = convert(tinyint, coalesce(
		(select dpc1.auth_update_f
		  from AAAMAINDB..data_prof_compo dpc1
		 where dpc1.data_profile_id = coalesce(convert( numeric(14) null, get_appcontext('TASC_CONTEXT', 'data_profile_id')), curr_usr.data_profile_id)
		   and E.data_secu_prof_id  = dpc1.data_secu_prof_id
		   and dpc1.auth_update_f = 1),
		(select dpc1.auth_update_f
		  from AAAMAINDB..data_prof_compo dpc1
		 where dpc1.data_profile_id = coalesce(convert( numeric(14) null, get_appcontext('TASC_CONTEXT', 'data_profile_id')), curr_usr.data_profile_id)
		   and E.data_secu_prof2_id = dpc1.data_secu_prof_id
		   and dpc1.auth_update_f = 1),
		   0)),
delete_right_f = convert(tinyint, coalesce(
		(select dpc1.auth_delete_f
		  from AAAMAINDB..data_prof_compo dpc1
		 where dpc1.data_profile_id = coalesce(convert( numeric(14) null, get_appcontext('TASC_CONTEXT', 'data_profile_id')), curr_usr.data_profile_id)
		   and E.data_secu_prof_id  = dpc1.data_secu_prof_id
		   and dpc1.auth_delete_f = 1),
		(select dpc1.auth_delete_f
		  from AAAMAINDB..data_prof_compo dpc1
		 where dpc1.data_profile_id = coalesce(convert( numeric(14) null, get_appcontext('TASC_CONTEXT', 'data_profile_id')), curr_usr.data_profile_id)
		   and E.data_secu_prof2_id = dpc1.data_secu_prof_id
		   and dpc1.auth_delete_f = 1),
		   0)),
main_business_entity_id = E.main_business_entity_id,
ud_id = ud.ud_id,
ud_pv_user_segment_e = ud.ud_pv_user_segment_e,
ud_pv_user_instr_admin_f = ud.ud_pv_user_instr_admin_f,
ud_pvr_pbs_serv_f = ud.ud_pvr_pbs_serv_f,
ud_dpm_user_group_e = ud.ud_dpm_user_group_e,
ud_user_profile_e = ud.ud_user_profile_e,
str_replace(str_replace(str_replace(str_replace(x.ext_denomination_en,char(13),null),char(10),null),char(9),null),'\n',null) as ext_denomination_en,
str_replace(str_replace(str_replace(str_replace(x.ext_denomination_fr,char(13),null),char(10),null),char(9),null),'\n',null) as ext_denomination_fr,
str_replace(str_replace(str_replace(str_replace(x.ext_denomination_de,char(13),null),char(10),null),char(9),null),'\n',null) as ext_denomination_de,
str_replace(str_replace(convert(varchar(20),x.ext_sql_calc_d,23),'T',' '),'.',null) as ext_sql_calc_d
/* security_level_e = 2 */
from AAAMAINDB..appl_user E inner join AAAMAINDB..appl_user curr_usr on curr_usr.code = suser_name() 
	inner join AAAMAINDB..ud_appl_user ud on E.id = ud.ud_id
	left outer join PERMTSLDB.tasclogin.x_appl_user x on E.id = x.x_id
where (exists ( select 1 from AAAMAINDB..data_prof_compo dpc1
       where dpc1.data_profile_id = coalesce (convert( numeric(14) null, get_appcontext( 'TASC_CONTEXT', 'data_profile_id' )), curr_usr.data_profile_id)
         and (E.data_secu_prof_id  = dpc1.data_secu_prof_id  or
              E.data_secu_prof2_id = dpc1.data_secu_prof_id)))
go

Grant Select on AAAREPDB..EDAG_appl_user_vw to GSTMT 
go
Grant Select on AAAREPDB..EDAG_appl_user_vw to tasc_tech 
go
Grant Select on AAAREPDB..EDAG_appl_user_vw to triplea 
go
