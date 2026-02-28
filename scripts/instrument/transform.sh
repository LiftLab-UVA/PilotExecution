#!/bin/bash

echo "compile success"

my_dir=$(cd "$(dirname "${BASH_SOURCE-$0}")"; pwd)
echo "my_dir is: $my_dir"
. ${my_dir}/common.sh

if [ $# -ne 2 ]; then
  echo "Usage: $0 fail"
  exit 1
fi
target_dir=$1
if [ ! -d ${target_dir} ]; then
  echo "target dir does not exist ${target_dir}"
  exit 1
fi

config_file=$2
# if config file does not exist, exit
if [ ! -f ${config_file} ]; then
  echo "config file does not exist ${config_file}"
  exit 1
fi

target_dir=${target_dir}/classes

rm -rf ${out_dir}/*
mkdir -p ${out_dir}

echo "Running RecoveryChecker on target directory: ${target_dir}"
echo "run recovery_checker"
run_recovery_checker -i ${target_dir} -e -C ${config_file}

