#!/system/bin/sh
# workloads: direct glibc (patched PT_INTERP) vs proot variants
D=/data/local/tmp/dd; cd $D
t() { local s=$(date +%s%N); "$@" >/dev/null 2>&1; local e=$(date +%s%N); echo $(( (e - s) / 1000000 )); }
base() { t ./run.sh $1 /usr/bin/true; }
W1='import json,email.parser,http.client,asyncio,decimal,sqlite3,argparse,subprocess,logging,xml.etree.ElementTree'
for v in direct vanilla fpoff fastpath; do
  input keyevent KEYCODE_WAKEUP
  for rep in 1 2 3; do
  if [ $v = direct ]; then
    export LD_LIBRARY_PATH=$D/mini/usr/lib PYTHONHOME=$D/mini/usr PYTHONDONTWRITEBYTECODE=1
    a=$(t $D/dbin/python3.14 -c "$W1")
    b=$(t $D/dbin/find $D/mini/usr/lib/python3.14 -type f)
    c=$(t $D/dbin/bash -c "for i in \$($D/dbin/seq 200); do $D/dbin/true; done")
    d=$(t $D/dbin/tar cf /dev/null $D/mini/usr/lib/python3.14)
    unset LD_LIBRARY_PATH PYTHONHOME
  else
    a=$(t ./run.sh $v /usr/bin/env PYTHONDONTWRITEBYTECODE=1 /usr/bin/python3.14 -c "$W1")
    b=$(t ./run.sh $v /usr/bin/find /usr/lib/python3.14 -type f)
    c=$(t ./run.sh $v /usr/bin/bash -c 'for i in $(seq 200); do /usr/bin/true; done')
    d=$(t ./run.sh $v /usr/bin/tar cf /dev/null /usr/lib/python3.14)
  fi
  [ $v = direct ] && z=0 || z=$(base $v)
  echo "$v rep$rep: proot-start=${z}ms python-imports=${a}ms find-8k-files=${b}ms 200x-exec=${c}ms tar-80MB=${d}ms"
  done
done
