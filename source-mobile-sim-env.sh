#!/bin/bash
# Assumes that the environment variable $MOBILE_SIM_HOME is set to this directory

### Setup Environment Variables

export CLASSPATH=$CLASSPATH:$MOBILE_SIM_HOME/java/mobilesim.jar

export PYTHONPATH=$PYTHONPATH:$MOBILE_SIM_HOME/python


### Setup Helper Scripts

source $MOBILE_SIM_HOME/scripts/custom_completions.sh

alias build_mobile_sim="$MOBILE_SIM_HOME/scripts/build_mobile_sim.sh"

alias run_mobile_sim="$MOBILE_SIM_HOME/scripts/run_mobile_sim.sh"
complete -F _mobile_worlds_completion run_mobile_sim

export PROBCOG_CONFIG=$MOBILE_SIM_HOME/config/robot.config.local



