#!/bin/ksh
######################################################################################
#
# UOB_EDAG_setup_Magnet.sh
#
# Setup script to run extraction for EDAG
#
# Exit values :       0 on SUCCESS , 255 on ERROR
#
######################################################################################
#
# Last updates
# DD/MM/YYYY    XXXXXX    Remarks
# 14/04/2025    CBTW      Creation
#
######################################################################################
procname=`basename $0`


######################################################################################
# - Initialization
######################################################################################

. $AAAHOME/admin/scripts/initaaa.cfg


######################################################################################
# Creating  folders and logs
######################################################################################
if [ ! -f $PRODLIB/scripts/EDAG_Extraction/Config/UOB_EDAG.config ];then
    echo "ERROR: Unable to locate $PRODLIB/scripts/EDAG_Extraction/Config/UOB_EDAG.config!"
    exit 255
else 
    . $PRODLIB/scripts/EDAG_Extraction/Config/UOB_EDAG.config

    touch ${SETUP_LOG}
    touch ${SETUP_WORK_LOG}
fi


# --- Start of functions -------------------------------------------------------------

# ------------------------------------------------------------------------------------
# create view
#

f_create_vw() {

    l_entity_vw=`echo $1 | sed -e 's/^/EDAG_/' | sed -e 's/ *$/_vw.sql/'`
    
    if [ ! -f  ${m_script_dir}/${l_entity_vw} ]; then
        timestamp; echo "${TIMESTAMP} | INFO | Msg: ${m_script_dir}/${l_entity_vw} NOT FOUND!!!" | tee -a ${SETUP_WORK_LOG}
    else
        isql -U$AAAUSER -P$AAAUSERPWD < ${m_script_dir}/${l_entity_vw} | tee -a ${SETUP_WORK_LOG}
        #echo "creating view ${m_script_dir}/${l_entity_vw}"
    fi
}
# end-fct f_create_vw-----------------------------------------------------------------

# --- End of functions ---------------------------------------------------------------


######################################################################################
# Main
######################################################################################

timestamp; echo "${TIMESTAMP} | INFO | Script $procname Started " | tee ${SETUP_LOG} ${SETUP_WORK_LOG}


while getopts :as: optname >/dev/null 2>&1
do
    case "${optname}" in
        a) # Option to create view for all entities
           p_mode="ALL"
          ;;
        s) # Option to create view for a single entity
           p_mode="SINGLE"
           p_entity="$OPTARG"
           ;;
        :) # for bad option
           echo "Missing parameter (check $OPTARG)"
           exit 255
           ;;
        ?) # for bad option
           echo "Bad option"
           exit 255
           ;;
    esac
done

if [ "$p_mode" == "" -o "$p_mode" == "ALL" ]; then
   MODE="ALL"
   timestamp; echo "${TIMESTAMP} | INFO | Mode : [ ${MODE} ] " | tee -a ${SETUP_LOG} ${SETUP_WORK_LOG}
fi

if [ "$p_mode" == "SINGLE" ]; then
   MODE="SINGLE"
   ENTITY=$p_entity
   timestamp; echo "${TIMESTAMP} | INFO | Mode : [ ${MODE} ] Entity : [ ${ENTITY} ]" | tee -a ${SETUP_LOG} ${SETUP_WORK_LOG}
fi


######################################################################################
# Setup Database Views For All Entities
######################################################################################

m_script_dir="$PRODLIB/scripts/EDAG_Extraction/Setup/Views"

set -A m_new_tap_core_list appl_user codification communication data_secu_prof dict_attribute dict_entity dict_perm_value list list_compo udt_pda_person udt_pda_product udt_sow_info4 udt_sow_info5 udt_sow_info6 udt_sow_info7 
set -A m_mod_tap_core_list instrument udt_callreport udt_ipq_questions udt_supplementary
set -A m_new_tap_fmt_list UOB_DS_PERF_CONTR UOB_OAP_REP_PTCC_BRM UOB_REP_PBR_VALOADD
set -A m_new_tap_misc_list RMAuditDetailsUpdate

if [ "${MODE}" = "ALL" ];then

    # AAAMAINDB Entities (new)
    for m_entity in "${m_new_tap_core_list[@]}"
    do
        f_create_vw $m_entity
    done
 
    # AAAMAINDB TAP Entities (existing)
    for m_entity in "${m_mod_tap_core_list[@]}"
    do
        f_create_vw $m_entity
    done 
    
    # TAP Formats
    for m_entity in "${m_new_tap_fmt_list[@]}"
    do
        f_create_vw $m_entity
    done
    
    # Misc
    for m_entity in "${m_new_tap_misc_list[@]}"
    do
        f_create_vw $m_entity
    done
    
    TAP_VIEW_COUNT=`cat ${SETUP_WORK_LOG} | grep "CREATING" | wc -l | sed 's/^[ ]*//' | sed 's/[ ]*$//'`
    ERROR_CATCH=`cat ${SETUP_WORK_LOG} | grep Msg | wc -l | sed 's/^[ ]*//' | sed 's/[ ]*$//'`
    
    timestamp; echo  "${TIMESTAMP} | INFO | Number of Views Attempted To Create : [ ${TAP_VIEW_COUNT} ]" | tee -a ${SETUP_LOG} ${SETUP_WORK_LOG}
    timestamp; echo "${TIMESTAMP} | INFO | Number of Errors  : [ ${ERROR_CATCH} ]" | tee -a ${SETUP_LOG} ${SETUP_WORK_LOG}
    
    if [ "${ERROR_CATCH}" -ge 1 ];then
        timestamp; echo "${TIMESTAMP} | ERROR | Setup has errors, please check logs ${SETUP_LOG} and ${SETUP_WORK_LOG}" | tee -a ${SETUP_LOG} ${SETUP_WORK_LOG}
        exit 255
    fi


######################################################################################
# Setup Database Views For Specified Entity
######################################################################################	
elif [  "${MODE}" = "SINGLE" ];then

   # Check if entity provided is in the Project Magnet list
    m_entity_found=`echo ${m_new_tap_core_list[@]} ${m_mod_tap_core_list[@]} ${m_new_tap_fmt_list[@]} ${m_new_tap_misc_list[@]} | fgrep -w ${ENTITY}`
   
   if [ "${m_entity_found}" == "" ]; then
       timestamp; echo "${TIMESTAMP} | INFO | Msg: Entity [${ENTITY}] not in the list!!!" | tee -a ${SETUP_WORK_LOG}
   else
       f_create_vw $ENTITY
   fi

    TAP_VIEW_COUNT=`cat ${SETUP_WORK_LOG} | grep "CREATING" | wc -l | sed 's/^[ ]*//' | sed 's/[ ]*$//`
    ERROR_CATCH=`cat ${SETUP_WORK_LOG} | grep Msg | wc -l | sed 's/^[ ]*//' | sed 's/[ ]*$//`

    timestamp; echo "${TIMESTAMP} | INFO | Number of Views Attempted To Create : [ ${TAP_VIEW_COUNT} ]" | tee -a ${SETUP_LOG} ${SETUP_WORK_LOG}
    timestamp; echo "${TIMESTAMP} | INFO | Number of Errors  : [ ${ERROR_CATCH} ]" | tee -a ${SETUP_LOG} ${SETUP_WORK_LOG}

    if [ "${ERROR_CATCH}" -ge 1 ];then
        timestamp; echo "${TIMESTAMP} | ERROR | Setup has errors, please check logs ${SETUP_LOG} and ${SETUP_WORK_LOG}" | tee -a ${SETUP_LOG} ${SETUP_WORK_LOG}
        exit 255
    fi


######################################################################################	
else

    echo "Please enter a valid input"
    timestamp; echo "${TIMESTAMP} | INFO | Invalid Input" >> ${SETUP_LOG}
fi


######################################################################################
timestamp; echo "${TIMESTAMP} | INFO | For log of this execution, pls see ${SETUP_LOG} and ${SETUP_WORK_LOG}" | tee -a ${SETUP_LOG} ${SETUP_WORK_LOG}
timestamp; echo "${TIMESTAMP} | INFO | Script $procname Ended " | tee -a ${SETUP_LOG} ${SETUP_WORK_LOG}

exit 0
