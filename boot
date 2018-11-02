#!/bin/sh -e

BUILDROOT_PATH=~/buildroot
RAMDISK_EXTRAS_PATH=~/ramdisk-extras

# define ids for all local boards
db410c_id=1dcd2e70
db820c_id=dd4541f9
qcs404_id=c33732d2
db845c_id=a5781354
db600c=10c7b36e

if [ -z "$1" ]; then
    echo "usage: ./boot [-n | -m] <board> [<target>]"
    echo "if <target> is set, we will try to boot the remote board with name <target>"
    exit
fi

if [ "$1" == -n ]; then
    power=0
    board=$2
    target=$3
    host=qc.lab
    timeout=1000
elif [ "$1" == -m ]; then
    power=0
    board=$2
    target=$3
    host=trans
    timeout=0
else
    power=1
    board=$1
    target=$2
    host=qc.lab
    timeout=1000
fi

eval id=\$${board}_id
if [ -z "$id" ]; then
    echo board not defined
    exit
fi

cmdline="root=/dev/ram0 console=tty0 console=ttyMSM0,115200n8 ignore_loglevel earlycon debug crashkernel=256M"
#cmdline="root=/dev/ram0 console=tty0 console=ttyMSM0,115200n8 ignore_loglevel earlycon debug crashkernel=256M initcall_debug=1 ftrace=function ftrace_filter=*stmmac*"

arch=arm64
compiler=aarch64-linux-gnu-
Image=arch/arm64/boot/Image
zImage=arch/arm64/boot/Image.gz

case $board in
    db410c*)
	soc=db410c
	pagesize=2048
	dtb=arch/arm64/boot/dts/qcom/apq8016-sbc.dtb
	;;
    qcs404*)
	soc=qcs404
	pagesize=2048
	dtb=arch/arm64/boot/dts/qcom/qcs404-evb-4000.dtb
	;;
    db820c*)
	soc=db820c
	pagesize=4096
	dtb=arch/arm64/boot/dts/qcom/apq8096-db820c.dtb
	;;
    db845c*)
	soc=db845c
	pagesize=4096
	dtb=arch/arm64/boot/dts/qcom/sdm845-db845c.dtb
	;;
    *)
	echo soc not defined
	exit
esac

rm -rf output/$board
mkdir -p output/$board

ARCH=$arch CROSS_COMPILE="ccache $compiler" make -j$(nproc --all)
cat $zImage $dtb > output/$board/zImage

mkdir -p output/$board/kernel/boot
cp $Image output/$board/kernel/boot/Image
(cd output/$board/kernel ; find . | cpio -o -H newc | gzip -9 > ../kernel.cpio.gz)

ARCH=$arch CROSS_COMPILE="ccache $compiler" make -j$(nproc --all) -s modules_install INSTALL_MOD_PATH=output/$board/modules INSTALL_MOD_STRIP=1
(cd output/$board/modules ; find . | cpio -o -H newc | gzip -9 > ../modules.cpio.gz)

cd output/$board

cat $BUILDROOT_PATH/initramfs.cpio.gz modules.cpio.gz > initramfs.cpio.gz
if [ -d $RAMDISK_EXTRAS_PATH ]; then
    cat $RAMDISK_EXTRAS_PATH/bin.cpio.gz >> initramfs.cpio.gz
    cat $RAMDISK_EXTRAS_PATH/wifi-$soc.cpio.gz >> initramfs.cpio.gz
    # do not include wifi password for now
    #[ $target ] || cat $RAMDISK_EXTRAS_PATH/passwd.cpio.gz >> initramfs.cpio.gz
fi
# we want to save Image in the ramdisk, so that we can use it for kexec/kdump
# but currently LK fails to boot this big ramdisk, so disable it for now...
#cat kernel.cpio.gz >> initramfs.cpio.gz

mkbootimg --kernel zImage --ramdisk initramfs.cpio.gz --output image --pagesize $pagesize --base 0x80000000 --cmdline "$cmdline"
cd -

if [ $target ]; then
    ~/cdba/cdba -T $timeout -b $target -h $host output/$board/image
else
    fastboot -s $id boot output/$board/image
    exit # rest of the code here is for my personal cdba replacement

    [ $power == 1 ] && ssh trans ~/bin/off

    rsync --progress -az output/$board/image trans:kernels/image-$board

    fastbootcmd="fastboot -s $id boot kernels/image-$board"
    echo "booting with: $fastbootcmd"
    #[ $power == 1 ] && sleep 3 && ssh trans ~/bin/on
    [ $power == 1 ] && sleep 15 && ssh trans ~/bin/on
    ssh trans "$fastbootcmd"
fi
