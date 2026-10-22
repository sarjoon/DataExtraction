#!/bin/ksh
#
# UOB_EDAG_setup.sh
######################################################################################
# Setup script to run extraction for EDAG
#
# Exit values		:       0 on SUCCESS , 255 on ERROR
#                       
######################################################################################
#
#	Date			Name				Description
#	05-Mar-2019	Jonathan Au (N+P)	Initial Version
#	19-Jun-2019	Jonathan Au(N+P)		De-scope 6 files
#	17-Jul-2019	Jonathan Au(N+P)		Added additional 7 files
#	29-Nov-2019	Gitacharyan R(N+P)		Added additional 3 tables for view creation
######################################################################################
procname=`basename $0`
PID=$$
######################################################################################
# - Initialization
######################################################################################

. $AAAHOME/admin/scripts/initaaa.cfg

if [ "$UOB2TA_HOME" = "" -o ! -x "$UOB2TA_HOME/UOB2GWP.cfg" ]
then
	echo "ERROR: \$UOB2TA_HOME is not defined or is set to a wrong value !"
	exit 255
fi

if [ "$GWPACK_HOME" = "" -o ! -x "$GWPACK_HOME/config/GWPACK.cfg" ]
then
	echo "ERROR: \$GWPACK_HOME is not defined or is set to a wrong value !"
	exit 255
fi

. $UOB2TA_HOME/UOB2GWP.cfg
. $GWPACK_HOME/config/GWPACK.cfg
. $GWPACK_HOME/scripts/GWPACK_Function.fct

######################################################################################
# Creating  folders and logs
######################################################################################
if [ ! -f $PRODLIB/scripts/EDAG_Extraction/Config/UOB_EDAG.config ];then
	echo "Missing Files. Please Re-Deploy Objects Into $PRODLIB/scripts/EDAG_Extraction/"
	exit 255
else 
	. $PRODLIB/scripts/EDAG_Extraction/Config/UOB_EDAG.config
	mkdir ${WORK_DIR} 
	#mkdir ${LOG_DIR}
	mkdir ${OUTPUT_DIR}
	#mkdir ${ARCHIVE_DIR}
	
	touch ${MAIN_LOG}
	touch ${WORK_LOG}
	touch ${SETUP_LOG}
	touch ${SETUP_WORK_LOG}
	
	chmod 755 ${EXTRACT_DIR}/*
	chmod 755 ${VIEWS_DIR}/*
	chmod 755 ${CONFIG_DIR}/*
	#chmod 755 ${LOG_DIR}/*
fi


######################################################################################
# Main
######################################################################################

timestamp; echo "${TIMESTAMP} | INFO | Script $procname Started " | tee ${SETUP_LOG} ${SETUP_WORK_LOG}


while getopts as optname #>/dev/null 2>&1
do

	case "${optname}" in
	
	a) 
	# Option to create view for all entities
	MODE="${OPTARG}"
	MODE="ALL"
	;;
	
	s) 
	# Option to create view for a single entity
	#s="${OPTARG}"
	MODE="SINGLE"
	ENTITY=$2
	;;
	
	*)
	# Invalid Argument
	echo "Invalid Argument"
	;;
	esac
done

timestamp; echo "${TIMESTAMP} | INFO | | Mode : [ ${MODE} ] " | tee -a ${SETUP_LOG} ${SETUP_WORK_LOG}


######################################################################################
# Setup Database Views For All Entities
######################################################################################
if [ "${MODE}" = "ALL" ];then
	
	# AAAMAINDB Entities
isql -U$AAAUSER -P$AAAUSERPWD < $PRODLIB/scripts/EDAG_Extraction/Setup/Views/EDAG_udt_ip_scale_vw.sql > ${SETUP_WORK_LOG}
isql -U$AAAUSER -P$AAAUSERPWD < $PRODLIB/scripts/EDAG_Extraction/Setup/Views/EDAG_udt_ip_questionnaire_vw.sql >> ${SETUP_WORK_LOG}
isql -U$AAAUSER -P$AAAUSERPWD < $PRODLIB/scripts/EDAG_Extraction/Setup/Views/EDAG_udt_assess_newaccount_vw.sql >> ${SETUP_WORK_LOG}
isql -U$AAAUSER -P$AAAUSERPWD < $PRODLIB/scripts/EDAG_Extraction/Setup/Views/EDAG_udt_docdefi_names_vw.sql >> ${SETUP_WORK_LOG}
isql -U$AAAUSER -P$AAAUSERPWD < $PRODLIB/scripts/EDAG_Extraction/Setup/Views/EDAG_udt_docnames_person_vw.sql >> ${SETUP_WORK_LOG}
isql -U$AAAUSER -P$AAAUSERPWD < $PRODLIB/scripts/EDAG_Extraction/Setup/Views/EDAG_udt_docnames_vw.sql >> ${SETUP_WORK_LOG}
isql -U$AAAUSER -P$AAAUSERPWD < $PRODLIB/scripts/EDAG_Extraction/Setup/Views/EDAG_udt_ipq_questions_vw.sql >> ${SETUP_WORK_LOG}
isql -U$AAAUSER -P$AAAUSERPWD < $PRODLIB/scripts/EDAG_Extraction/Setup/Views/EDAG_type_vw.sql >> ${SETUP_WORK_LOG}
isql -U$AAAUSER -P$AAAUSERPWD < $PRODLIB/scripts/EDAG_Extraction/Setup/Views/EDAG_sector_vw.sql >> ${SETUP_WORK_LOG}
isql -U$AAAUSER -P$AAAUSERPWD < $PRODLIB/scripts/EDAG_Extraction/Setup/Views/EDAG_geographic_vw.sql >> ${SETUP_WORK_LOG}
isql -U$AAAUSER -P$AAAUSERPWD < $PRODLIB/scripts/EDAG_Extraction/Setup/Views/EDAG_deposit_vw.sql >> ${SETUP_WORK_LOG}
isql -U$AAAUSER -P$AAAUSERPWD < $PRODLIB/scripts/EDAG_Extraction/Setup/Views/EDAG_currency_vw.sql >> ${SETUP_WORK_LOG}
isql -U$AAAUSER -P$AAAUSERPWD < $PRODLIB/scripts/EDAG_Extraction/Setup/Views/EDAG_instrument_vw.sql >> ${SETUP_WORK_LOG}
#isql -U$AAAUSER -P$AAAUSERPWD < $PRODLIB/scripts/EDAG_Extraction/Setup/Views/EDAG_curr_chrono_vw.sql >> ${SETUP_WORK_LOG}
isql -U$AAAUSER -P$AAAUSERPWD < $PRODLIB/scripts/EDAG_Extraction/Setup/Views/EDAG_rating_attrib_vw.sql >> ${SETUP_WORK_LOG}
isql -U$AAAUSER -P$AAAUSERPWD < $PRODLIB/scripts/EDAG_Extraction/Setup/Views/EDAG_instr_price_vw.sql >> ${SETUP_WORK_LOG}
isql -U$AAAUSER -P$AAAUSERPWD < $PRODLIB/scripts/EDAG_Extraction/Setup/Views/EDAG_port_chrono_vw.sql >> ${SETUP_WORK_LOG}
isql -U$AAAUSER -P$AAAUSERPWD < $PRODLIB/scripts/EDAG_Extraction/Setup/Views/EDAG_exchange_event_vw.sql >> ${SETUP_WORK_LOG}
isql -U$AAAUSER -P$AAAUSERPWD < $PRODLIB/scripts/EDAG_Extraction/Setup/Views/EDAG_interest_cond_vw.sql >> ${SETUP_WORK_LOG}
#isql -U$AAAUSER -P$AAAUSERPWD < $PRODLIB/scripts/EDAG_Extraction/Setup/Views/EDAG_instr_deposit_vw.sql >> ${SETUP_WORK_LOG}
isql -U$AAAUSER -P$AAAUSERPWD < $PRODLIB/scripts/EDAG_Extraction/Setup/Views/EDAG_notepad_vw.sql >> ${SETUP_WORK_LOG}
isql -U$AAAUSER -P$AAAUSERPWD < $PRODLIB/scripts/EDAG_Extraction/Setup/Views/EDAG_udt_kyc_referral_vw.sql >> ${SETUP_WORK_LOG}
isql -U$AAAUSER -P$AAAUSERPWD < $PRODLIB/scripts/EDAG_Extraction/Setup/Views/EDAG_udt_kyc_submit_vw.sql >> ${SETUP_WORK_LOG}
isql -U$AAAUSER -P$AAAUSERPWD < $PRODLIB/scripts/EDAG_Extraction/Setup/Views/EDAG_udt_vc_assess_vw.sql >> ${SETUP_WORK_LOG}
isql -U$AAAUSER -P$AAAUSERPWD < $PRODLIB/scripts/EDAG_Extraction/Setup/Views/EDAG_udt_kyc_basic_vw.sql >> ${SETUP_WORK_LOG}
isql -U$AAAUSER -P$AAAUSERPWD < $PRODLIB/scripts/EDAG_Extraction/Setup/Views/EDAG_udt_invprofile_vw.sql >> ${SETUP_WORK_LOG}
isql -U$AAAUSER -P$AAAUSERPWD < $PRODLIB/scripts/EDAG_Extraction/Setup/Views/EDAG_udt_supplementary_vw.sql >> ${SETUP_WORK_LOG}
isql -U$AAAUSER -P$AAAUSERPWD < $PRODLIB/scripts/EDAG_Extraction/Setup/Views/EDAG_udt_sow_info3_vw.sql >> ${SETUP_WORK_LOG}
isql -U$AAAUSER -P$AAAUSERPWD < $PRODLIB/scripts/EDAG_Extraction/Setup/Views/EDAG_udt_sow_info2_vw.sql >> ${SETUP_WORK_LOG}
isql -U$AAAUSER -P$AAAUSERPWD < $PRODLIB/scripts/EDAG_Extraction/Setup/Views/EDAG_udt_pictrust_vw.sql >> ${SETUP_WORK_LOG}
isql -U$AAAUSER -P$AAAUSERPWD < $PRODLIB/scripts/EDAG_Extraction/Setup/Views/EDAG_udt_personid_vw.sql >> ${SETUP_WORK_LOG}
isql -U$AAAUSER -P$AAAUSERPWD < $PRODLIB/scripts/EDAG_Extraction/Setup/Views/EDAG_udt_person_vw.sql >> ${SETUP_WORK_LOG}
#isql -U$AAAUSER -P$AAAUSERPWD < $PRODLIB/scripts/EDAG_Extraction/Setup/Views/EDAG_udt_pda_person.sql >> ${SETUP_WORK_LOG}
isql -U$AAAUSER -P$AAAUSERPWD < $PRODLIB/scripts/EDAG_Extraction/Setup/Views/EDAG_udt_opening_details_vw.sql >> ${SETUP_WORK_LOG}
isql -U$AAAUSER -P$AAAUSERPWD < $PRODLIB/scripts/EDAG_Extraction/Setup/Views/EDAG_udt_migration1_vw.sql >> ${SETUP_WORK_LOG}
isql -U$AAAUSER -P$AAAUSERPWD < $PRODLIB/scripts/EDAG_Extraction/Setup/Views/EDAG_udt_doc_defi_vw.sql >> ${SETUP_WORK_LOG}
isql -U$AAAUSER -P$AAAUSERPWD < $PRODLIB/scripts/EDAG_Extraction/Setup/Views/EDAG_udt_doc_checklist_vw.sql >> ${SETUP_WORK_LOG}
#isql -U$AAAUSER -P$AAAUSERPWD < $PRODLIB/scripts/EDAG_Extraction/Setup/Views/EDAG_udt_crs_vw.sql >> ${SETUP_WORK_LOG}
isql -U$AAAUSER -P$AAAUSERPWD < $PRODLIB/scripts/EDAG_Extraction/Setup/Views/EDAG_udt_crlink_vw.sql >> ${SETUP_WORK_LOG}
isql -U$AAAUSER -P$AAAUSERPWD < $PRODLIB/scripts/EDAG_Extraction/Setup/Views/EDAG_udt_cr_vw.sql >> ${SETUP_WORK_LOG}
isql -U$AAAUSER -P$AAAUSERPWD < $PRODLIB/scripts/EDAG_Extraction/Setup/Views/EDAG_udt_contactpoint_vw.sql >> ${SETUP_WORK_LOG}
isql -U$AAAUSER -P$AAAUSERPWD < $PRODLIB/scripts/EDAG_Extraction/Setup/Views/EDAG_udt_sow_info1_vw.sql >> ${SETUP_WORK_LOG}
isql -U$AAAUSER -P$AAAUSERPWD < $PRODLIB/scripts/EDAG_Extraction/Setup/Views/EDAG_udt_callreport_vw.sql >> ${SETUP_WORK_LOG}
isql -U$AAAUSER -P$AAAUSERPWD < $PRODLIB/scripts/EDAG_Extraction/Setup/Views/EDAG_udt_doc_checklist_person_vw.sql >> ${SETUP_WORK_LOG}
isql -U$AAAUSER -P$AAAUSERPWD < $PRODLIB/scripts/EDAG_Extraction/Setup/Views/EDAG_synonym_vw.sql >> ${SETUP_WORK_LOG}
isql -U$AAAUSER -P$AAAUSERPWD < $PRODLIB/scripts/EDAG_Extraction/Setup/Views/EDAG_rating_vw.sql >> ${SETUP_WORK_LOG}
#isql -U$AAAUSER -P$AAAUSERPWD < $PRODLIB/scripts/EDAG_Extraction/Setup/Views/EDAG_exch_format_vw.sql >> ${SETUP_WORK_LOG}
isql -U$AAAUSER -P$AAAUSERPWD < $PRODLIB/scripts/EDAG_Extraction/Setup/Views/EDAG_udt_add_names_vw.sql >> ${SETUP_WORK_LOG}
isql -U$AAAUSER -P$AAAUSERPWD < $PRODLIB/scripts/EDAG_Extraction/Setup/Views/EDAG_udt_sow_names_vw.sql >> ${SETUP_WORK_LOG}
isql -U$AAAUSER -P$AAAUSERPWD < $PRODLIB/scripts/EDAG_Extraction/Setup/Views/EDAG_udt_aml_question_lib_vw.sql >> ${SETUP_WORK_LOG}
	
	# OCSMAINDB Entities
isql -U$OCSUSER -P$OCSUSERPWD < $PRODLIB/scripts/EDAG_Extraction/Setup/Views/EDAG_CUSTOM_cdm_clientrelationo_vw.sql >> ${SETUP_WORK_LOG}
isql -U$OCSUSER -P$OCSUSERPWD < $PRODLIB/scripts/EDAG_Extraction/Setup/Views/EDAG_cdm_roletype_vw.sql >> ${SETUP_WORK_LOG}
#isql -U$OCSUSER -P$OCSUSERPWD < $PRODLIB/scripts/EDAG_Extraction/Setup/Views/EDAG_cdm_role_vw.sql >> ${SETUP_WORK_LOG}
isql -U$OCSUSER -P$OCSUSERPWD < $PRODLIB/scripts/EDAG_Extraction/Setup/Views/EDAG_cdm_preferredtableclassmapping_vw.sql >> ${SETUP_WORK_LOG}
isql -U$OCSUSER -P$OCSUSERPWD < $PRODLIB/scripts/EDAG_Extraction/Setup/Views/EDAG_cdm_commercial_roles_vw.sql >> ${SETUP_WORK_LOG}
isql -U$OCSUSER -P$OCSUSERPWD < $PRODLIB/scripts/EDAG_Extraction/Setup/Views/EDAG_cdm_clientrelation_type_vw.sql >> ${SETUP_WORK_LOG}
isql -U$OCSUSER -P$OCSUSERPWD < $PRODLIB/scripts/EDAG_Extraction/Setup/Views/EDAG_cdm_clientrelation_status_vw.sql >> ${SETUP_WORK_LOG}
isql -U$OCSUSER -P$OCSUSERPWD < $PRODLIB/scripts/EDAG_Extraction/Setup/Views/EDAG_CUSTOM_cdm_riskprofile_vw.sql >> ${SETUP_WORK_LOG}
isql -U$OCSUSER -P$OCSUSERPWD < $PRODLIB/scripts/EDAG_Extraction/Setup/Views/EDAG_CUSTOM_cdm_services_vw.sql >> ${SETUP_WORK_LOG}
isql -U$OCSUSER -P$OCSUSERPWD < $PRODLIB/scripts/EDAG_Extraction/Setup/Views/EDAG_CUSTOM_cdm_p_riskprofile_vw.sql >> ${SETUP_WORK_LOG}
isql -U$OCSUSER -P$OCSUSERPWD < $PRODLIB/scripts/EDAG_Extraction/Setup/Views/EDAG_CUSTOM_cdm_p_financialenvi_vw.sql >> ${SETUP_WORK_LOG}
isql -U$OCSUSER -P$OCSUSERPWD < $PRODLIB/scripts/EDAG_Extraction/Setup/Views/EDAG_cdm_PBICLRL_vw.sql >> ${SETUP_WORK_LOG}
isql -U$OCSUSER -P$OCSUSERPWD < $PRODLIB/scripts/EDAG_Extraction/Setup/Views/EDAG_cdm_PBIPDTL_vw.sql >> ${SETUP_WORK_LOG}
isql -U$OCSUSER -P$OCSUSERPWD < $PRODLIB/scripts/EDAG_Extraction/Setup/Views/EDAG_cdm_PBIPID_vw.sql >> ${SETUP_WORK_LOG}
isql -U$OCSUSER -P$OCSUSERPWD < $PRODLIB/scripts/EDAG_Extraction/Setup/Views/EDAG_cdm_PBICORP_vw.sql >> ${SETUP_WORK_LOG}
isql -U$OCSUSER -P$OCSUSERPWD < $PRODLIB/scripts/EDAG_Extraction/Setup/Views/EDAG_cdm_PBIPRM_vw.sql >> ${SETUP_WORK_LOG}
isql -U$OCSUSER -P$OCSUSERPWD < $PRODLIB/scripts/EDAG_Extraction/Setup/Views/EDAG_cdm_PBIPRTC_vw.sql >> ${SETUP_WORK_LOG}
isql -U$OCSUSER -P$OCSUSERPWD < $PRODLIB/scripts/EDAG_Extraction/Setup/Views/EDAG_cdm_PBIPRTY_vw.sql >> ${SETUP_WORK_LOG}



TAP_VIEW_COUNT=`cat ${SETUP_WORK_LOG} | grep "CREATING" | wc -l | sed 's/^[ ]*//' | sed 's/[ ]*$//'`
ERROR_CATCH=`cat ${SETUP_WORK_LOG} | grep Msg | wc -l | sed 's/^[ ]*//' | sed 's/[ ]*$//'`

timestamp; echo  "${TIMESTAMP} | INFO | | Number of Views Attempted To Create : [ ${TAP_VIEW_COUNT} ]" | tee -a ${SETUP_LOG} ${SETUP_WORK_LOG}
timestamp; echo "${TIMESTAMP} | INFO | | Number of Errors  : [ ${ERROR_CATCH} ]" | tee -a ${SETUP_LOG} ${SETUP_WORK_LOG}

if [ "${ERROR_CATCH}" -ge 1 ];then
	timestamp; echo "${TIMESTAMP} | ERROR | Setup has errors, please check logs" | tee -a ${SETUP_LOG} ${SETUP_WORK_LOG}
	exit 255
fi


######################################################################################
# Setup Database Views For Specified Entity
######################################################################################	
elif [  "${MODE}" = "SINGLE" ];then

	if [[ "${ENTITY}" == *"cdm"* ]];then
		SINGLE_VIEW=`echo ${ENTITY} | sed -e 's/^/EDAG_/' | sed -e 's/ *$/_vw.sql/'`
		isql -U$OCSUSER -P$OCSUSERPWD < $PRODLIB/scripts/EDAG_Extraction/Setup/Views/$SINGLE_VIEW | tee -a ${SETUP_WORK_LOG}
		
		TAP_VIEW_COUNT=`cat ${SETUP_WORK_LOG} | grep "CREATING" | wc -l | sed 's/^[ ]*//' | sed 's/[ ]*$//'`
		ERROR_CATCH=`cat ${SETUP_WORK_LOG} | grep Msg | wc -l | sed 's/^[ ]*//' | sed 's/[ ]*$//'`

		timestamp; echo  "${TIMESTAMP} | INFO | |  Number of Views Attempted To Create : [ ${TAP_VIEW_COUNT} ]" | tee -a ${SETUP_LOG} ${SETUP_WORK_LOG}
		timestamp; echo "${TIMESTAMP} | INFO | | Number of Errors  : [ ${ERROR_CATCH} ]" | tee -a ${SETUP_LOG} ${SETUP_WORK_LOG}
		
		if [ "${ERROR_CATCH}" -ge 1 ];then
			timestamp; echo "${TIMESTAMP} | ERROR | Setup has errors, please check logs" | tee -a ${SETUP_LOG} ${SETUP_WORK_LOG}
			exit 255
		fi
	
	else 
		SINGLE_VIEW=`echo ${ENTITY} | sed -e 's/^/EDAG_/' | sed -e 's/ *$/_vw.sql/'`
		isql -U$AAAUSER -P$AAAUSERPWD < $PRODLIB/scripts/EDAG_Extraction/Setup/Views/$SINGLE_VIEW | tee -a ${SETUP_WORK_LOG}
		
		TAP_VIEW_COUNT=`cat ${SETUP_WORK_LOG} | grep "CREATING" | wc -l | sed 's/^[ ]*//' | sed 's/[ ]*$//`
		ERROR_CATCH=`cat ${SETUP_WORK_LOG} | grep Msg | wc -l | sed 's/^[ ]*//' | sed 's/[ ]*$//`

		timestamp; echo  "${TIMESTAMP} | INFO | | Number of Views Attempted To Create : [ ${TAP_VIEW_COUNT} ]" | tee -a ${SETUP_LOG} ${SETUP_WORK_LOG}
		timestamp; echo "${TIMESTAMP} | INFO | | Number of Errors  : [ ${ERROR_CATCH} ]" | tee -a ${SETUP_LOG} ${SETUP_WORK_LOG}
		
		if [ "${ERROR_CATCH}" -ge 1 ];then
			timestamp; echo "${TIMESTAMP} | ERROR | Setup has errors, please check logs" | tee -a ${SETUP_LOG} ${SETUP_WORK_LOG}
			exit 255
		fi
	fi

	

######################################################################################	
else
	
	echo "Please enter a valid input"
	timestamp; echo "${TIMESTAMP} | INFO | | Invalid Input" >> ${SETUP_LOG}
fi


######################################################################################
timestamp; echo "${TIMESTAMP} | INFO | | Script $procname Ended " | tee -a ${SETUP_LOG} ${SETUP_WORK_LOG}

exit 0
