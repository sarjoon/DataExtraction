#!/bin/ksh
#
# UOB_EDAG_Control.sh
######################################################################################
# Main script to run extraction for EDAG
#
# Default Mode : UOB_EDAG_Control.sh  -a 
# Extract Single Entity: UOB_EDAG_Control.sh  -s <Name of Entity>
# Sets Number of Extraction Jobs: UOB_EDAG_Control.sh  -c <Number of Jobs>
#
#
# Exit values		:       0 on SUCCESS , 255 on ERROR
#                       
######################################################################################
#
#	Date			Name				Description
#	05-Mar-2019	Jonathan Au (N+P)	Initial Version
#	18-Mar-2019	Jonathan Au (N+P)	Accepts multiple parameters. Allows extraction of TAP/CDM entities separately
#	16-May-2019	Jonathan Au (N+P)	Modified code to only exit the job at the very end of the script if there are errors
#	19-Jun-2019	Jonathan Au(N+P)		Removed 6 files from being generated. Product features not in used by UOB. JIRA PEDDAGML-1612
#   13-Mar-2025 CBTW                Project Magnet: Added option to pass a file containing list of entities to extract
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
	#exit 255
fi

if [ "$GWPACK_HOME" = "" -o ! -x "$GWPACK_HOME/config/GWPACK.cfg" ]
then
	echo "ERROR: \$GWPACK_HOME is not defined or is set to a wrong value !"
	#exit 255
fi

. $UOB2TA_HOME/UOB2GWP.cfg
. $GWPACK_HOME/config/GWPACK.cfg
. $GWPACK_HOME/scripts/GWPACK_Function.fct
. $PRODLIB/scripts/EDAG_Extraction/Config/UOB_EDAG.config


######################################################################################
# Main
######################################################################################
timestamp; echo "${TIMESTAMP} | INFO | Main Batch Script $procname Started " | tee ${MAIN_LOG} ${WORK_LOG}



######################################################################################
# Archive and clean up previous data
######################################################################################
timestamp; echo "${TIMESTAMP} | INFO | Archiving Previous Output Directory" | tee -a ${MAIN_LOG} ${WORK_LOG}
#tar -cvf ${ARCHIVE_DIR}/EDAG_Extraction_`date +%Y%m%d`.tar.gz ${OUTPUT_DIR}
rm ${OUTPUT_DIR}/*


######################################################################################
# Checks Parameters
######################################################################################
while getopts "atos:c:f:" optname >/dev/null 2>&1
do
	case $optname in
	a) # Option to extract all entities
	MODE="ALL"
	;;
	
	s) # Option to extract a single entity
	MODE="SINGLE"
	ENTITIES="$OPTARG"
	;;
	
	t) # Option to extract all TAP entities
	MODE="TAP_ONLY"
	;;
	
	o) # Option to extract all OCS entities
	MODE="OCS_ONLY"
	;;
	c) # Option to extract a single entity
	MAX_PARALLEL_EXTRACTION_JOBS="$OPTARG"
	;;

	f) # Option to extract entities listed in the FILE
	MODE="FILE"
	ADHOC_FILE="$OPTARG"
	;;
	esac
done


timestamp; echo "${TIMESTAMP} | INFO | Mode : [ ${MODE} ] | Number Of Parallel Jobs Set At : [ ${MAX_PARALLEL_EXTRACTION_JOBS} ]" | tee -a ${MAIN_LOG} ${WORK_LOG}


######################################################################################
# Batch Extraction For All Entities Listed in EXTRACTION_ENTITIES_LIST
######################################################################################
if [ "${MODE}" = "ALL" ];then


	EXTRACTION_COUNTER=0
	TOTAL_ENTITIES_TO_EXTRACT=`cat -n ${EXTRACTION_ENTITIES_LIST} | awk '{print $1}' | wc -l | sed 's/^[ ]*//' | sed 's/[ ]*$//'`
	timestamp; echo "${TIMESTAMP} | INFO | Extraction Prepared For : [ ${TOTAL_ENTITIES_TO_EXTRACT} ] Entities" | tee -a ${MAIN_LOG} ${WORK_LOG}
	
	# Begin Reading Entity List
	while read -r ENTITY HASHSUM || [ -n "${HASHSUM}" ] || [ -n "${ENTITY}" ]
		do
		
		# Check if extraction script exists
		if [ ! -f ${EXTRACT_DIR}/EDAG_Create_Interface.sh ];then
			timestamp; echo "${TIMESTAMP} | FATAL ERROR | Extraction Script For ${ENTITY} Does Not Exist. Exiting Script" | tee -a ${MAIN_LOG} ${WORK_LOG}
			exit 255
		fi
		
		
		# Check number of extractions ongoing at the moment
		CHECK_SCRIPT=`ps -ef | grep EDAG | wc -l | awk '{print $1}'`
		
		timestamp; echo "${TIMESTAMP} | INFO | Number Of Extraction Jobs Currently Running : [ ${CHECK_SCRIPT} ] " | tee -a ${MAIN_LOG} ${WORK_LOG}
	
	
		# Pause and wait for extractions to be less than 
		if [ "${CHECK_SCRIPT}" -ge "${MAX_PARALLEL_EXTRACTION_JOBS}" ];then
			sleep 10
			CHECK_SCRIPT=`ps -ef | grep EDAG | wc -l | awk '{print $1}'`
		fi
	
	
			# Loop to extract entities defined in Extraction List
			timestamp; echo "${TIMESTAMP} | INFO | Calling Extraction Script For  : [ ${ENTITY} ] " | tee -a ${MAIN_LOG} ${WORK_LOG}
			${EXTRACT_DIR}/EDAG_Create_Interface.sh ${MAIN_LOG} ${WORK_LOG} ${ENTITY} ${HASHSUM}  &
			EXTRACTION_COUNTER=`expr ${EXTRACTION_COUNTER} + 1`
		done < ${EXTRACTION_ENTITIES_LIST}


		## - Check the Process before exiting
		CHECK_SCRIPT=`ps -ef | grep EDAG | wc -l | awk '{print $1}'`
		timestamp; echo "${TIMESTAMP} | INFO | Checking Number Of Extraction Jobs Currently Running Before Exiting Batch Job. Jobs Currently Running  : [ ${CHECK_SCRIPT} ] " | tee -a ${MAIN_LOG} ${WORK_LOG}
			
			# Prepares to exit loop
			# Checking if greater than 2 because the GREP command will be counted as well by the PS -EF command
			while [ "$CHECK_SCRIPT" -gt 2 ]
				do
					sleep 10
					timestamp; echo "${TIMESTAMP} | INFO | Checking Number Of Extraction Jobs Currently Running Before Exiting Batch Job. Jobs Currently Running  : [ ${CHECK_SCRIPT} ] " | tee -a ${MAIN_LOG} ${WORK_LOG}
					CHECK_SCRIPT=`ps -ef | grep EDAG | wc -l | awk '{print $1}'`
				done
				
		timestamp; echo "${TIMESTAMP} | INFO | Number of Entities Processed : [ ${EXTRACTION_COUNTER} ]" | tee -a ${MAIN_LOG} ${WORK_LOG}


######################################################################################		
# Extraction For A Single Entity
######################################################################################
elif [  "${MODE}" = "SINGLE" ];then
	
	# Check if extraction script exists
	if [ ! -f ${EXTRACT_DIR}/EDAG_Create_Interface.sh ];then
		timestamp; echo "${TIMESTAMP} | FATAL ERROR | Extraction Script For Does Not Exist. Exiting Script" | tee -a ${MAIN_LOG} ${WORK_LOG}
		exit 255
	fi

	for ENTITY in ${ENTITIES}
	do
		# Retrieving HASHSUM variable
		HASHSUM=`cat ${EXTRACTION_ENTITIES_LIST} | grep -w ${ENTITY} | awk '{print $2}`
	
		timestamp; echo "${TIMESTAMP} | INFO | Calling Extraction Script For  : [ ${ENTITY} ] | HASHSUM : [ ${HASHSUM} ]" | tee -a ${MAIN_LOG} ${WORK_LOG}
		${EXTRACT_DIR}/EDAG_Create_Interface.sh ${MAIN_LOG} ${WORK_LOG} ${ENTITY} ${HASHSUM}  
	
	
		if [ "$?" -ne 0 ];then
			timestamp; echo "${TIMESTAMP} | ERROR | Extraction Error. Check $WORK_LOG" | tee -a ${MAIN_LOG} ${WORK_LOG}
			#exit 255
		fi
	done

######################################################################################		
# Extraction For TAP Entities
######################################################################################
elif [ "${MODE}" = "TAP_ONLY" ];then

	EXTRACTION_COUNTER=0
	TOTAL_ENTITIES_TO_EXTRACT=`cat -n ${EXTRACTION_TAP_ENTITIES_LIST} | awk '{print $1}' | wc -l | sed 's/^[ ]*//' | sed 's/[ ]*$//'`
	timestamp; echo "${TIMESTAMP} | INFO | Extraction Prepared For : [ ${TOTAL_ENTITIES_TO_EXTRACT} ] Entities" | tee -a ${MAIN_LOG} ${WORK_LOG}
	
	# Begin Reading Entity List
	while read -r ENTITY HASHSUM || [ -n "${HASHSUM}" ] || [ -n "${ENTITY}" ]
		do
		
		# Check if extraction script exists
		if [ ! -f ${EXTRACT_DIR}/EDAG_Create_Interface.sh ];then
			timestamp; echo "${TIMESTAMP} | FATAL ERROR | Extraction Script For ${ENTITY} Does Not Exist. Exiting Script" | tee -a ${MAIN_LOG} ${WORK_LOG}
			exit 255
		fi
		
		
		# Check number of extractions ongoing at the moment
		CHECK_SCRIPT=`ps -ef | grep EDAG | wc -l | awk '{print $1}'`
		
		timestamp; echo "${TIMESTAMP} | INFO | Number Of Extraction Jobs Currently Running : [ ${CHECK_SCRIPT} ] " | tee -a ${MAIN_LOG} ${WORK_LOG}
	
	
		# Pause and wait for extractions to be less than 
		if [ "${CHECK_SCRIPT}" -ge "${MAX_PARALLEL_EXTRACTION_JOBS}" ];then
			sleep 10
			CHECK_SCRIPT=`ps -ef | grep EDAG | wc -l | awk '{print $1}'`
		fi
	
	
			# Loop to extract entities defined in Extraction List
			timestamp; echo "${TIMESTAMP} | INFO | Calling Extraction Script For  : [ ${ENTITY} ] " | tee -a ${MAIN_LOG} ${WORK_LOG}
			${EXTRACT_DIR}/EDAG_Create_Interface.sh ${MAIN_LOG} ${WORK_LOG} ${ENTITY} ${HASHSUM}  &
			EXTRACTION_COUNTER=`expr ${EXTRACTION_COUNTER} + 1`
		done < ${EXTRACTION_TAP_ENTITIES_LIST}


		## - Check the Process before exiting
		CHECK_SCRIPT=`ps -ef | grep EDAG | wc -l | awk '{print $1}'`
		timestamp; echo "${TIMESTAMP} | INFO | Checking Number Of Extraction Jobs Currently Running Before Exiting Batch Job. Jobs Currently Running  : [ ${CHECK_SCRIPT} ] " | tee -a ${MAIN_LOG} ${WORK_LOG}
			
			# Prepares to exit loop
			# Checking if greater than 2 because the GREP command will be counted as well by the PS -EF command
			while [ "$CHECK_SCRIPT" -gt 2 ]
				do
					sleep 10
					timestamp; echo "${TIMESTAMP} | INFO | Checking Number Of Extraction Jobs Currently Running Before Exiting Batch Job. Jobs Currently Running  : [ ${CHECK_SCRIPT} ] " | tee -a ${MAIN_LOG} ${WORK_LOG}
					CHECK_SCRIPT=`ps -ef | grep EDAG | wc -l | awk '{print $1}'`
				done
				
		timestamp; echo "${TIMESTAMP} | INFO | Number of Entities Processed : [ ${EXTRACTION_COUNTER} ]" | tee -a ${MAIN_LOG} ${WORK_LOG}

######################################################################################		
# Extraction For OCS Entities
######################################################################################		
elif [ "${MODE}" = "OCS_ONLY" ];then

	EXTRACTION_COUNTER=0
	TOTAL_ENTITIES_TO_EXTRACT=`cat -n ${EXTRACTION_CDM_ENTITIES_LIST} | awk '{print $1}' | wc -l | sed 's/^[ ]*//' | sed 's/[ ]*$//'`
	timestamp; echo "${TIMESTAMP} | INFO | Extraction Prepared For : [ ${TOTAL_ENTITIES_TO_EXTRACT} ] Entities" | tee -a ${MAIN_LOG} ${WORK_LOG}
	
	# Begin Reading Entity List
	while read -r ENTITY HASHSUM || [ -n "${HASHSUM}" ] || [ -n "${ENTITY}" ]
		do
		
		# Check if extraction script exists
		if [ ! -f ${EXTRACT_DIR}/EDAG_Create_Interface.sh ];then
			timestamp; echo "${TIMESTAMP} | FATAL ERROR | Extraction Script For ${ENTITY} Does Not Exist. Exiting Script" | tee -a ${MAIN_LOG} ${WORK_LOG}
			exit 255
		fi
		
		
		# Check number of extractions ongoing at the moment
		CHECK_SCRIPT=`ps -ef | grep EDAG | wc -l | awk '{print $1}'`
		
		timestamp; echo "${TIMESTAMP} | INFO | Number Of Extraction Jobs Currently Running : [ ${CHECK_SCRIPT} ] " | tee -a ${MAIN_LOG} ${WORK_LOG}
	
	
		# Pause and wait for extractions to be less than 
		if [ "${CHECK_SCRIPT}" -ge "${MAX_PARALLEL_EXTRACTION_JOBS}" ];then
			sleep 10
			CHECK_SCRIPT=`ps -ef | grep EDAG | wc -l | awk '{print $1}'`
		fi
	
	
			# Loop to extract entities defined in Extraction List
			timestamp; echo "${TIMESTAMP} | INFO | Calling Extraction Script For  : [ ${ENTITY} ] " | tee -a ${MAIN_LOG} ${WORK_LOG}
			${EXTRACT_DIR}/EDAG_Create_Interface.sh ${MAIN_LOG} ${WORK_LOG} ${ENTITY} ${HASHSUM}  &
			EXTRACTION_COUNTER=`expr ${EXTRACTION_COUNTER} + 1`
		done < ${EXTRACTION_CDM_ENTITIES_LIST}


		## - Check the Process before exiting
		CHECK_SCRIPT=`ps -ef | grep EDAG | wc -l | awk '{print $1}'`
		timestamp; echo "${TIMESTAMP} | INFO | Checking Number Of Extraction Jobs Currently Running Before Exiting Batch Job. Jobs Currently Running  : [ ${CHECK_SCRIPT} ] " | tee -a ${MAIN_LOG} ${WORK_LOG}
			
			# Prepares to exit loop
			# Checking if greater than 2 because the GREP command will be counted as well by the PS -EF command
			while [ "$CHECK_SCRIPT" -gt 2 ]
				do
					sleep 10
					timestamp; echo "${TIMESTAMP} | INFO | Checking Number Of Extraction Jobs Currently Running Before Exiting Batch Job. Jobs Currently Running  : [ ${CHECK_SCRIPT} ] " | tee -a ${MAIN_LOG} ${WORK_LOG}
					CHECK_SCRIPT=`ps -ef | grep EDAG | wc -l | awk '{print $1}'`
				done
				
		timestamp; echo "${TIMESTAMP} | INFO | Number of Entities Processed : [ ${EXTRACTION_COUNTER} ]" | tee -a ${MAIN_LOG} ${WORK_LOG}

######################################################################################		
# Extraction For FILE containing list of entities to extract
######################################################################################		
elif [ "${MODE}" = "FILE" ];then

    # Check if config file  exists
	if [ ! -f "$CONFIG_DIR/$ADHOC_FILE" ]
	then
		timestamp; echo "${TIMESTAMP} | FATAL ERROR | Extraction List [$CONFIG_DIR/$ADHOC_FILE] Does Not Exist. Exiting Script" | tee -a ${MAIN_LOG} ${WORK_LOG}
		exit 255
	fi
	EXTRACTION_FILE_ENTITIES_LIST=$CONFIG_DIR/$ADHOC_FILE

	EXTRACTION_COUNTER=0
	TOTAL_ENTITIES_TO_EXTRACT=`cat -n ${EXTRACTION_FILE_ENTITIES_LIST} | awk '{print $1}' | wc -l | sed 's/^[ ]*//' | sed 's/[ ]*$//'`
	timestamp; echo "${TIMESTAMP} | INFO | Extraction Prepared For : [ ${TOTAL_ENTITIES_TO_EXTRACT} ] Entities" | tee -a ${MAIN_LOG} ${WORK_LOG}
	
	# Begin Reading Entity List
	while read -r ENTITY HASHSUM || [ -n "${HASHSUM}" ] || [ -n "${ENTITY}" ]
		do
		
		# Check if extraction script exists
		if [ ! -f ${EXTRACT_DIR}/EDAG_Create_Interface.sh ];then
			timestamp; echo "${TIMESTAMP} | FATAL ERROR | Extraction Script For ${ENTITY} Does Not Exist. Exiting Script" | tee -a ${MAIN_LOG} ${WORK_LOG}
			exit 255
		fi
		
		
		# Check number of extractions ongoing at the moment
		CHECK_SCRIPT=`ps -ef | grep EDAG | wc -l | awk '{print $1}'`
		
		timestamp; echo "${TIMESTAMP} | INFO | Number Of Extraction Jobs Currently Running : [ ${CHECK_SCRIPT} ] " | tee -a ${MAIN_LOG} ${WORK_LOG}
	
	
		# Pause and wait for extractions to be less than 
		if [ "${CHECK_SCRIPT}" -ge "${MAX_PARALLEL_EXTRACTION_JOBS}" ];then
			sleep 10
			CHECK_SCRIPT=`ps -ef | grep EDAG | wc -l | awk '{print $1}'`
		fi
	
	
			# Loop to extract entities defined in Extraction List
			timestamp; echo "${TIMESTAMP} | INFO | Calling Extraction Script For  : [ ${ENTITY} ] " | tee -a ${MAIN_LOG} ${WORK_LOG}
			${EXTRACT_DIR}/EDAG_Create_Interface.sh ${MAIN_LOG} ${WORK_LOG} ${ENTITY} ${HASHSUM}  &
			EXTRACTION_COUNTER=`expr ${EXTRACTION_COUNTER} + 1`
		done < ${EXTRACTION_FILE_ENTITIES_LIST}


		## - Check the Process before exiting
		CHECK_SCRIPT=`ps -ef | grep EDAG | wc -l | awk '{print $1}'`
		timestamp; echo "${TIMESTAMP} | INFO | Checking Number Of Extraction Jobs Currently Running Before Exiting Batch Job. Jobs Currently Running  : [ ${CHECK_SCRIPT} ] " | tee -a ${MAIN_LOG} ${WORK_LOG}
			
			# Prepares to exit loop
			# Checking if greater than 2 because the GREP command will be counted as well by the PS -EF command
			while [ "$CHECK_SCRIPT" -gt 2 ]
				do
					sleep 10
					timestamp; echo "${TIMESTAMP} | INFO | Checking Number Of Extraction Jobs Currently Running Before Exiting Batch Job. Jobs Currently Running  : [ ${CHECK_SCRIPT} ] " | tee -a ${MAIN_LOG} ${WORK_LOG}
					CHECK_SCRIPT=`ps -ef | grep EDAG | wc -l | awk '{print $1}'`
				done
				
		timestamp; echo "${TIMESTAMP} | INFO | Number of Entities Processed : [ ${EXTRACTION_COUNTER} ]" | tee -a ${MAIN_LOG} ${WORK_LOG}

else
	echo "Please enter a valid input"
	#exit 255
fi

######################################################################################
# Error Checking
######################################################################################
ERROR_CHECKER=`cat ${WORK_LOG} | grep -e WARNING -e ERROR | wc -l | awk '{print $1}'`


if [ "${ERROR_CHECKER}" -ge 1 ];then
	timestamp; echo "${TIMESTAMP} | ERROR | There was an error in the logs. Please check $WORK_LOG" | tee -a ${MAIN_LOG} ${WORK_LOG}
	exit 255
fi

######################################################################################
# Moving Files to Connect Transfer Directory
######################################################################################
#timestamp; echo "${TIMESTAMP} | INFO | Copying Interface Files To ${EDAG_CDTRANSFER_DIR}" | tee -a ${MAIN_LOG} ${WORK_LOG}
#cp ${OUTPUT_DIR}/* ${EDAG_CDTRANSFER_DIR}


#if [ $? -ne 0 ];then
#	timestamp; echo "${TIMESTAMP} | WARNING | Copying Interface Files : [ FAILED ]" | tee -a ${MAIN_LOG} ${WORK_LOG}
#elif [ $? -eq 0 ];then
#	timestamp; echo "${TIMESTAMP} | INFO | Copying Interface Files : [ SUCCEED ]" | tee -a ${MAIN_LOG} ${WORK_LOG}
#fi
######################################################################################


timestamp; echo "${TIMESTAMP} | INFO | Main Batch Script $procname Ended " | tee -a ${MAIN_LOG} ${WORK_LOG}
exit 0
