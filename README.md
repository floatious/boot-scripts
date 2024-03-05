# Build and boot a kernel from git using TFTP

This guide will explain how you can build and boot a kernel from git on
a single-board computer (SBC) using the TFTP support in u-boot.

## Reasoning
The reason for doing this is because as a kernel developer, you want to:
1. Work in a separate git tree (most likely a git worktree).
In other words, we don't want to deal with patches or a kernel directory
which is not a git tree (which is default behavior in buildroot).
2. Quickly be able to boot and test your changes.
In other words, we do not want to write the kernel to a SD-card each and
every time we want to test a change.

We will start off with a buildroot defconfig, which will generate a sdcard.img
which contains the bootloader (u-boot), the root file system, and a kernel.

We will write sdcard.img to the SD-card, but instead of using the kernel from
the SD-card, we will load a kernel that we have built separately (outside
buildroot) using TFTP.

## What you will need
 - Devboard with Ethernet.
 - Ethernet support for that devboard in u-boot.
 - Ethernet cable from the devboard to your network.
 - SD-card that will contain the root file system.
 - Philips Hue bridge.
 - Philips Hue smart plug.
 - DHCP server on your network.
 - TFTP server (will be installed on your development machine).

This guide will use a Radxa Rock5b devboard, and a development machine
running Fedora Linux.

## TFTP server
### Installing a TFTP server on your development machine
```
sudo dnf install tftpd-server tftp
sudo chmod 777 /var/lib/tftpboot/
sudo systemctl start tftp.socket
sudo systemctl enable tftp.socket
sudo firewall-cmd --add-service=tftp --permanent
sudo firewall-cmd --reload
```

### Verify that the TFTP server is working
```
dd if=/dev/zero of=/var/lib/tftpboot/test bs=1M count=1
tftp localhost
tftp> verbose
tftp> get test
tftp> quit
rm /var/lib/tftpboot/test
```
The get command should yield something like:

getting from localhost:test to test [netascii]
Received 1048576 bytes in 0.0 seconds [186637662 bit/s]

## Buildroot
### Clone buildroot
```
mkdir ~/src
cd ~/src
git clone git://git.buildroot.net/buildroot buildroot-rock5b
git checkout 2023.11
cd buildroot-rock5b
```

### Update u-boot version
Edit configs/rock5b_defconfig

From:
```
BR2_TARGET_UBOOT_CUSTOM_VERSION_VALUE="2023.07"
```
To:
```
BR2_TARGET_UBOOT_CUSTOM_VERSION_VALUE="2024.01"
```
This is because Ethernet support for Rock5b was first added in u-boot 2024.01.

### Make u-boot load kernel via TFTP
Edit board/radxa/rock5b/boot.cmd

From:
```
setenv bootargs root=/dev/mmcblk0p2 rw rootfstype=ext4 clkin_hz=(25000000) earlycon clk_ignore_unused earlyprintk console=ttyS2,1500000n8 rootwait
fatload mmc 1:1 ${loadaddr} image.itb
bootm ${loadaddr}
```
To:
```
pci enum
setenv autoload no
dhcp
setenv serverip 192.168.1.10
tftp ${loadaddr} rock5b/image.itb
setenv bootargs root=PARTLABEL=rootfs clkin_hz=(25000000) earlycon clk_ignore_unused earlyprintk console=ttyS2,1500000n8 rootwait
bootm ${loadaddr}
```
Replace 192.168.1.10 with the IP address of your development machine.

The prefix to image.itb is important, as it will determine the board type
that you will use.

(Later, you will see that there are also other board types defined,
e.g. "rock5b-ep".)

### Build buildroot and write to SD-card
```
make rock5b_defconfig
make
sudo dd if=output/images/sdcard.img of=/dev/sdX
( echo w ) | sudo fdisk /dev/sdX && sync && sudo eject /dev/sdX
```
Replace /dev/sdX with your SD-card.

After writing, plug in the SD-card to your devboard.

## build_boot script

The build_boot script is meant to be executed while standing in a kernel git
tree or a git worktree.

The build_boot script will:
1. Build the kernel
2. Generate a flattened image tree blob (image.itb)
3. Copy the image.itb to your TFTP directory (inside a "board type" subdirectory)
4. Toggle the power on your Philips Hue smart plug, such that u-boot will load
   the recently built kernel

### Install kernel prerequisites
```
sudo dnf install gcc-aarch64-linux-gnu
sudo dnf install uboot-tools
```
This is needed because we are building the kernel outside buildroot.

### Clone build_boot script
```
cd ~/src
git clone https://github.com/floatious/boot-scripts.git
cd boot-scripts
```

### Set Philips Hue specific variables
Edit build_boot

Set variables **huehostname**, **hueapikey** and **huelightid** according to
your setup.

If you cloned buildroot to a path different from the path used in the
instructions above, make sure that you also update the **its** variables.

### Install build_boot script
```
sudo cp build_boot /usr/bin/
```

## Kernel
### Clone kernel
```
cd ~/src
git clone git://git.kernel.org/pub/scm/linux/kernel/git/torvalds/linux.git
cd linux
```

### Create a kernel config
```
ARCH=arm64 CROSS_COMPILE=aarch64-linux-gnu- make defconfig
```
This only needs to be done once.

### Build and boot kernel
```
cd ~/src/linux
build_boot rock5b
```
Run the build_boot script while standing in a kernel git tree or a git
worktree.

The script will automatically build the kernel, build image.itb, copy image.itb
to a subdirectory in your TFTP directory, and the toggle power on your Philps
Hue smart plug, such that u-boot will load the recently built kernel via TFTP.

Lean back and enjoy the feeling of not needing to plug and unplug
your SD-card anymore.
