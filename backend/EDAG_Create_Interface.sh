#!/bin/ksh
#
# EDAG_Create_Interface.sh
######################################################################################
# Script generates interface files for EDAG
#
# EDAG_Create_Interface <Entity Name> <Hashsum Colum>
#
# Exit values		:       0 on SUCCESS , 255 on ERROR
#     
######################################################################################
#	Date			Name				Description
#	05-Mar-2019	Jonathan Au (N+P)	Initial Version
#	16-May-2019	Jonathan Au (N+P)	Modified code to only exit the job at the very end of the script if there are errors
#	28-May-2019	Jonathan Au (N+P)	Added error handling should hashsum comes as NULL. When NULL, set hashsum to zero
#	19-Jun-2019	Jonathan Au(N+P)		Added logic to extract Qlik tables
#	14-Aug-2019	Jonathan Au(N+P)	Modified logic for hashsum. To calculate hashsum from bcp out instead of querying from database
#	14-Jul-2023	OA		 			Modified logic for hashsum to use awk and bc as fix for SALES-2129
#	05-Feb-2025	CBTW/OA 			Changing the logic on Creating the final output file SALES-2698
######################################################################################

######################################################################################
# - Initialization
######################################################################################
procname=`basename $0`
PID=$$

. $AAAHOME/admin/scripts/initaaa.cfg > /dev/null 2>&1

if [ "$UOB2TA_HOME" = "" -o ! -x "$UOB2TA_HOME/UOB2GWP.cfg" ]
then
	echo "ERROR: \$UOB2TA_HOME is not defined or is set to a wrong value !"
	#exit 255
fi

if [ "$GWPACK_HOME" = "" -o ! -x "$GWPACK_HOME/config/GWPACK.cfg" ]
then
	echo "ERROR: \$GWPACK_HOME is not defined or is set to a wrong value !"
	#exit 255
fi

. $UOB2TA_HOME/UOB2GWP.cfg > /dev/null 2>&1
. $GWPACK_HOME/config/GWPACK.cfg > /dev/null 2>&1
. $GWPACK_HOME/scripts/GWPACK_Function.fct > /dev/null 2>&1
. $PRODLIB/scripts/EDAG_Extraction/Config/UOB_EDAG.config > /dev/null 2>&1
######################################################################################
# Variables
######################################################################################
MAIN_LOG=$1
WORK_LOG=$2
ENTITY=$3
HASHSUM=$4


OUTPUT_FILE=${OUTPUT_DIR}/`echo ${ENTITY} | tr "[a-z]" "[A-Z]"`
CAST_RESULT=${WORK_DIR}/${ENTITY}.log
touch $OUTPUT_FILE
touch ${CAST_RESULT}


######################################################################################
timestamp; echo "${TIMESTAMP} | INFO | Script $procname Started. Processing Entity : [ ${ENTITY} ] " | tee -a ${MAIN_LOG} ${WORK_LOG}


######################################################################################
# Determines Database, User and Password depending on entity
######################################################################################
# If entity belongs to OCS/CDM
if [[ "${ENTITY}" == *"cdm"* ]];then

		USER=$OCSUSER
		PASSWORD=$OCSUSERPWD
		DATABASE="OCSMAINDB"
					# Checks if view exists in AAAREPDB. Views are only created if entity allows creation of UD fields, if customized views were not created for entity in AAAREPDB, use standard entity view in AAAMAINDB
					VIEW_CHECKER=`isql -U$OCSUSER -P$OCSUSERPWD -S$DSQUERY -b <<-EOT
					set nocount on
					go

					use OCSMAINDB
					go 

					IF EXISTS (SELECT 1 FROM sysobjects o, sysusers u WHERE o.uid=u.uid AND o.name = 'EDAG_${ENTITY}_vw' AND o.type = 'V')
						print "1"
					
					ELSE
						BEGIN
						print "0"
					END
					go
					EOT`
	
	
	# Sets the name of the view and database to be BCP out
	if [ "${VIEW_CHECKER}" = "1" ];then

		ENTITY_TO_EXTRACT="EDAG_${ENTITY}_vw"	
		timestamp; echo "${TIMESTAMP} | INFO | View Exists For [ ${ENTITY} ]" >> ${WORK_LOG}
		
	elif [ "${VIEW_CHECKER}" = "0" ];then
		
		ENTITY_TO_EXTRACT="${ENTITY}"
		timestamp; echo "${TIMESTAMP} | WARNING | View Does Not Exists For [ ${ENTITY} ]" >> ${WORK_LOG}
		
	else
		timestamp; echo "${TIMESTAMP} | WARNING | Invalid Entry [ ${ENTITY} ]" | tee -a ${MAIN_LOG} ${WORK_LOG}
		#exit 255
	fi
	
	
	timestamp; echo "${TIMESTAMP} | INFO | [ ${ENTITY} ] Will Be Extracted from ${DATABASE} " >> ${WORK_LOG}
else

# If entity belongs to Triple'A
	USER=$AAAUSER
	PASSWORD=$AAAUSERPWD
	
					# Checks if view exists in AAAREPDB. Views are only created if entity allows creation of UD fields, if customized views were not created for entity in AAAREPDB, use standard entity view in AAAMAINDB
					VIEW_CHECKER=`isql -U$AAAUSER -P$AAAUSERPWD -S$DSQUERY -b <<-EOT
					set nocount on
					go

					use AAAREPDB
					go 

					IF EXISTS (SELECT 1 FROM sysobjects o, sysusers u WHERE o.uid=u.uid AND o.name = 'EDAG_${ENTITY}_vw' AND o.type = 'V')
						print "1"
					
					ELSE
						BEGIN
						print "0"
					END
					go
					EOT`
	
	# Sets the name of the view and database to be BCP out
	if [ "${VIEW_CHECKER}" = "1" ];then
		
		DATABASE="AAAREPDB"
		ENTITY_TO_EXTRACT="EDAG_${ENTITY}_vw"
		timestamp; echo "${TIMESTAMP} | INFO | View Exists For [ ${ENTITY} ]" >> ${WORK_LOG}
		
	elif [ "${VIEW_CHECKER}" = "0" ];then
		
		timestamp; echo "${TIMESTAMP} | ERROR | View Does Not Exists For [ ${ENTITY} ]" >> ${WORK_LOG}
		
	else
		timestamp; echo "${TIMESTAMP} | WARNING | Invalid Entry [ ${ENTITY} ]" | tee -a ${MAIN_LOG} ${WORK_LOG}
		#exit 255
	fi
	
	
	timestamp; echo "${TIMESTAMP} | INFO | [ ${ENTITY} ] Will Be Extracted from ${DATABASE} " >> ${WORK_LOG}
fi


######################################################################################
# Main
######################################################################################
timestamp; echo "${TIMESTAMP} | INFO | Begin BCP For [ ${ENTITY} ] | Table To BCP : [ ${ENTITY_TO_EXTRACT} ] | Database : [ ${DATABASE} ] " >> ${WORK_LOG}

######################################################################################
# BCP the view out of the database
######################################################################################
bcp ${DATABASE}..${ENTITY_TO_EXTRACT} out ${WORK_DIR}/${ENTITY}.out -t -U$USER -P$PASSWORD -S$DSQUERY -Jutf8 -c > ${CAST_RESULT}


	if [ "$?" -ne 0 ];then
		timestamp; echo "${TIMESTAMP} | ERROR | BCP Extraction Error For Entity : [ ${ENTITY} ]  " | tee -a ${MAIN_LOG} ${WORK_LOG}
		timestamp; echo "${TIMESTAMP} | ERROR | Error Was : [ `cat ${CAST_RESULT}` ]  " | tee -a ${WORK_LOG}
		#exit 255
	fi


######################################################################################
# Retrieving details for Header and Trailer
######################################################################################
	BUSINESS_DATE=`isql -U$AAAUSER -P$AAAUSERPWD -S$DSQUERY -b -w99999<<-EOT | sed 's/^[ ]*//' | sed 's/ [ ]*$//'
	set nocount on
	go

	use AAAMAINDB
	go

	SELECT value_n
	FROM appl_param
	WHERE param_name = 'CURRENT_PROCESSING_DATE'
	go
	EOT`

	
	DATE_TIME=`date +%Y%m%d%H%M%S`
	RECORD_COUNT=`cat ${WORK_DIR}/${ENTITY}.out | wc -l | sed 's/^[ \t]*//' | sed 's/[ \t]*$//'`
	ROWS_COPIED=`cat ${CAST_RESULT} | grep "rows copied" | awk '{print $1}'`

	
	if [ "${RECORD_COUNT}" -ne "${ROWS_COPIED}" ];then
		timestamp; echo "${TIMESTAMP} | WARNING | Mismatch Of Rows Copied By BCP & Rows In Output File For Entity : [ ${ENTITY} ]" | tee -a ${WORK_LOG}
	fi

######################################################################################	
# Populating Hashsum
######################################################################################
	if [ -z "${HASHSUM}" ];then
		HASHSUM_TO_CSV=""
	else
		#14-07-2023 - SALES-2129
		#PRE_HASHSUM_TO_CSV=`awk -v var=${HASHSUM} -F"" '{total += $var} END {printf("%28d\n",total)}' ${WORK_DIR}/${ENTITY}.out | sed 's/^[ ]*//' | sed 's/ [ ]*$//'`
		 PRE_HASHSUM_TO_CSV=`awk -v var=${HASHSUM} -F '\\' '{print "a+=" $var} END {print "a"}' "${WORK_DIR}/${ENTITY}.out" | sed 's/^[ ]*//' | sed 's/ [ ]*$//' | bc`
		
		if [[ "${PRE_HASHSUM_TO_CSV}" = "NULL" || "${PRE_HASHSUM_TO_CSV}" = "" ]];then
			HASHSUM_TO_CSV="0"
		else
			HASHSUM_TO_CSV="${PRE_HASHSUM_TO_CSV}"
		fi
	fi
	
	
######################################################################################	
# Creating the final output file
######################################################################################
#SALES-2698
#echo "HPBISG${BUSINESS_DATE}${DATE_TIME}" > ${OUTPUT_FILE}
#cat ${WORK_DIR}/${ENTITY}.out | sed -e 's/^/D/' >> ${OUTPUT_FILE}
#echo "T${RECORD_COUNT}${HASHSUM_TO_CSV}" >> ${OUTPUT_FILE}

{
	echo "HPBISG${BUSINESS_DATE}${DATE_TIME}"
	sed 's/^/D/'
	echo "T${RECORD_COUNT}${HASHSUM_TO_CSV}"
} < ${WORK_DIR}/${ENTITY}.out > ${OUTPUT_FILE}

######################################################################################	
# Moving Interface file to Connect Direct Transfer Directory
######################################################################################
timestamp; echo "${TIMESTAMP} | INFO | Copying Interface File : [ ${ENTITY} ] To CD Directory" | tee -a ${MAIN_LOG} ${WORK_LOG}
if [ -f "${OUTPUT_FILE}" ];then
	echo "Output File : " ${OUTPUT_FILE}
		
		if [[ "${OUTPUT_FILE}" == *"CDM_PBI"* ]];then
			OUTPUT_FILE_RENAMED=`echo ${OUTPUT_FILE} | sed 's/CDM_//'`
			mv ${OUTPUT_FILE} ${OUTPUT_FILE_RENAMED}
			cp ${OUTPUT_FILE_RENAMED} ${EDAG_CDTRANSFER_DIR}/
		else
			cp ${OUTPUT_FILE} ${EDAG_CDTRANSFER_DIR}/`echo ${ENTITY} | tr "[a-z]" "[A-Z]"`
		fi
	

	
	
	timestamp; echo "${TIMESTAMP} | INFO | Copy Result For : [ ${ENTITY} ] is $?" | tee -a  ${WORK_LOG}
	
else
	timestamp; echo "${TIMESTAMP} | WARNING | Missing Interface File : [ ${ENTITY} ] in [ ${OUTPUT_DIR} ]" | tee -a ${MAIN_LOG} ${WORK_LOG}
fi

if [[ "${OUTPUT_FILE}" == *"CDM_PBI"* ]];then
	# Error checking on file transfer
	ENTITY=`echo ${ENTITY} | sed 's/CDM_//' | sed 's/cdm_//'`
	if [ ! -f ${EDAG_CDTRANSFER_DIR}/${ENTITY} ];then
		timestamp; echo "${TIMESTAMP} | WARNING | Interface File For [ ${ENTITY} ] : [ FAILED ]. Unable to find in : [ ${EDAG_CDTRANSFER_DIR} ]" | tee -a ${MAIN_LOG} ${WORK_LOG}
		echo ""
	fi

else
	# Error checking on file transfer
	ENTITY=`echo ${ENTITY} | tr "[a-z]" "[A-Z]"`
	if [ ! -f ${EDAG_CDTRANSFER_DIR}/${ENTITY} ];then
		timestamp; echo "${TIMESTAMP} | WARNING | Interface File For [ ${ENTITY} ] : [ FAILED ]. Unable to find in : [ ${EDAG_CDTRANSFER_DIR} ]" | tee -a ${MAIN_LOG} ${WORK_LOG}
		echo ""
	fi
fi

timestamp; echo "${TIMESTAMP} | INFO | Finished Copying Interface File : [ ${ENTITY} ] To CD Directory" | tee -a ${MAIN_LOG} ${WORK_LOG}
######################################################################################

#rm ${WORK_DIR}/${ENTITY}.out
rm $CAST_RESULT


timestamp; echo "${TIMESTAMP} | INFO | Script $procname Ended. Processed Entity : [ ${ENTITY} ]" | tee -a ${MAIN_LOG} ${WORK_LOG}


exit 0
