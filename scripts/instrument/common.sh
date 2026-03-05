this_dir=$(cd "$(dirname "${BASH_SOURCE-$0}")"; pwd)
common_src=$(dirname "${this_dir}")/lib/common.sh
echo "common_src is: $common_src"
if [ ! -f ${common_src} ]; then
  echo "Could not find ${common_src}"
  exit 1
fi
source ${common_src}
if [ -z "${out_dir}" -o ${out_dir} == "." -o ${out_dir} == "/" ]; then
  echo "Output dir set to '${out_dir}', will shoot ourselves in the foot..."
  exit 1
fi
