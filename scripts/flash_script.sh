#MAGISK
##########################################################################################
#
# Magisk Flash Script
# by topjohnwu
#
# This script will detect, construct the environment for Magisk
# It will then call boot_patch.sh to patch the boot image
#
##########################################################################################

##########################################################################################
# Preparation
##########################################################################################

# This path should work in any cases
TMPDIR=/dev/tmp

INSTALLER=$TMPDIR/install
COMMONDIR=$INSTALLER/common
APK=$COMMONDIR/magisk.apk
CHROMEDIR=$INSTALLER/chromeos

# Default permissions
umask 022

OUTFD=$2
ZIP=$3

if [ ! -f $COMMONDIR/util_functions.sh ]; then
  echo "! Unable to extract zip file!"
  exit 1
fi

# Load utility fuctions
. $COMMONDIR/util_functions.sh

get_outfd

##########################################################################################
# Detection
##########################################################################################

ui_print "************************"
ui_print "* Magisk v$MAGISK_VER Installer"
ui_print "************************"

is_mounted /data || mount /data || is_mounted /cache || mount /cache || abort "! Unable to mount partitions"
mount_partitions

# read override variables
getvar KEEPVERITY
getvar KEEPFORCEENCRYPT
getvar BOOTIMAGE

[ -z $BOOTIMAGE ] && abort "! Unable to detect boot image"
ui_print "- Found boot/ramdisk image: $BOOTIMAGE"

if [ ! -z $DTBOIMAGE ]; then
  ui_print "- Found dtbo image: $DTBOIMAGE"
  # Disable dtbo patch by default
  [ -z $KEEPVERITY ] && KEEPVERITY=true
fi

# Detect version and architecture
api_level_arch_detect

[ $API -lt 21 ] && abort "! Magisk is only for Lollipop 5.0+ (SDK 21+)"

ui_print "- Device platform: $ARCH"

BINDIR=$INSTALLER/$ARCH
chmod -R 755 $CHROMEDIR $BINDIR

# Check if system root is installed and remove
remove_system_su

##########################################################################################
# Environment
##########################################################################################

ui_print "- Constructing environment"

# Check if we can actually access the data (DE storage)
DATA=false
if grep ' /data ' /proc/mounts | grep -vq 'tmpfs'; then
  [ ! -d /data/adb ] && mkdir /data/adb
  touch /data/adb/.write_test && rm /data/adb/.write_test && DATA=true
fi

if $DATA; then
  MAGISKBIN=/data/adb/magisk
  run_migrations
else
  MAGISKBIN=/cache/data_bin
fi

# Copy required files
rm -rf $MAGISKBIN/* 2>/dev/null
mkdir -p $MAGISKBIN 2>/dev/null
cp -af $BINDIR/. $COMMONDIR/. $CHROMEDIR $TMPDIR/bin/busybox $MAGISKBIN
chmod -R 755 $MAGISKBIN

# addon.d
if [ -d /system/addon.d ]; then
  ui_print "- Adding addon.d survival script"
  mount -o rw,remount /system
  cp -af $INSTALLER/common/99-magisk.sh /system/addon.d/99-magisk.sh
  chmod 755 /system/addon.d/99-magisk.sh
fi

$BOOTMODE || recovery_actions

##########################################################################################
# Boot patching
##########################################################################################

eval $BOOTSIGNER -verify < $BOOTIMAGE && BOOTSIGNED=true
$BOOTSIGNED && ui_print "- Boot image is signed with AVB 1.0"

SOURCEDMODE=true
cd $MAGISKBIN

# Source the boot patcher
. $COMMONDIR/boot_patch.sh "$BOOTIMAGE"

flash_boot_image new-boot.img "$BOOTIMAGE"
rm -f new-boot.img

if [ -f stock_boot* ]; then
  rm -f /data/stock_boot* 2>/dev/null
  $DATA && mv stock_boot* /data
fi

$KEEPVERITY || patch_dtbo_image

if [ -f stock_dtbo* ]; then
  rm -f /data/stock_dtbo* 2>/dev/null
  $DATA && mv stock_dtbo* /data
fi

cd /
# Cleanups
$BOOTMODE || recovery_cleanup
rm -rf $TMPDIR

ui_print "- Done"
exit 0
