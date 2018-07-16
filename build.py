#!/usr/bin/env python3
import sys
import os
import subprocess

if os.name == 'nt':
	import colorama
	colorama.init()

def error(str):
	print('\n' + '\033[41m' + str + '\033[0m' + '\n')
	sys.exit(1)

def header(str):
	print('\n' + '\033[44m' + str + '\033[0m' + '\n')

# Environment checks
if not sys.version_info >= (3, 5):
	error('Requires Python 3.5+')

if 'ANDROID_HOME' not in os.environ:
	error('Please add Android SDK path to ANDROID_HOME environment variable!')

try:
	subprocess.run(['java', '-version'], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
except FileNotFoundError:
	error('Please install JDK and make sure \'java\' is available in PATH')

import argparse
import multiprocessing
import zipfile
import datetime
import errno
import shutil
import lzma
import base64
import tempfile

if 'ANDROID_NDK_HOME' in os.environ:
	ndk_build = os.path.join(os.environ['ANDROID_NDK_HOME'], 'ndk-build')
else:
	ndk_build = os.path.join(os.environ['ANDROID_HOME'], 'ndk-bundle', 'ndk-build')

cpu_count = multiprocessing.cpu_count()
gradlew = os.path.join('.', 'gradlew.bat' if os.name == 'nt' else 'gradlew')
archs = ['arm64-v8a', 'armeabi-v7a', 'x86', 'x86_64']

def mv(source, target):
	try:
		shutil.move(source, target)
	except:
		pass

def cp(source, target):
	try:
		shutil.copyfile(source, target)
		print('cp: {} -> {}'.format(source, target))
	except:
		pass

def rm(file):
	try:
		os.remove(file)
	except OSError as e:
		if e.errno != errno.ENOENT:
			raise

def mkdir(path, mode=0o777):
	try:
		os.mkdir(path, mode)
	except:
		pass

def mkdir_p(path, mode=0o777):
	os.makedirs(path, mode, exist_ok=True)

def zip_with_msg(zipfile, source, target):
	if not os.path.exists(source):
		error('{} does not exist! Try build \'binary\' and \'apk\' before zipping!'.format(source))
	print('zip: {} -> {}'.format(source, target))
	zipfile.write(source, target)

def build_all(args):
	build_apk(args)
	build_binary(args)
	zip_main(args)
	zip_uninstaller(args)

def collect_binary():
	for arch in archs:
		mkdir_p(os.path.join('native', 'out', arch))
		for bin in ['magisk', 'magiskinit', 'magiskboot', 'busybox', 'b64xz']:
			source = os.path.join('native', 'libs', arch, bin)
			target = os.path.join('native', 'out', arch, bin)
			mv(source, target)

def build_binary(args):
	# If nothing specified, build everything
	try:
		targets = args.target
	except:
		targets = []

	if len(targets) == 0:
		targets = ['magisk', 'magiskinit', 'magiskboot', 'busybox', 'b64xz']

	header('* Building binaries: ' + ' '.join(targets))

	# Force update logging.h timestamp to trigger recompilation for the flags to make a difference
	os.utime(os.path.join('native', 'jni', 'include', 'logging.h'))

	# Basic flags
	base_flags = 'MAGISK_VERSION=\"{}\" MAGISK_VER_CODE={} MAGISK_DEBUG={}'.format(config['version'], config['versionCode'],
		'' if args.release else '-DMAGISK_DEBUG')

	if 'magisk' in targets:
		# Magisk is special case as it is a dependency of magiskinit
		proc = subprocess.run('{} -C native {} B_MAGISK=1 -j{}'.format(ndk_build, base_flags, cpu_count), shell=True, stdout=STDOUT)
		if proc.returncode != 0:
			error('Build Magisk binary failed!')
		collect_binary()

	old_plat = False
	flags = base_flags

	if 'b64xz' in targets:
		flags += ' B_BXZ=1'
		old_plat = True

	if 'magiskinit' in targets:
		for arch in archs:
			bin_file = os.path.join('native', 'out', arch, 'magisk')
			if not os.path.exists(bin_file):
				error('Build "magisk" before building "magiskinit"')
			with open(os.path.join('native', 'out', arch, 'binaries_arch_xz.h'), 'w') as out:
				with open(bin_file, 'rb') as src:
					xz_dump(src, out, 'magisk_xz')

		stub_apk = os.path.join(config['outdir'], 'stub-release.apk')
		if not os.path.exists(stub_apk):
			error('Build release stub APK before building "magiskinit"')

		with open(os.path.join('native', 'out', 'binaries_xz.h'), 'w') as out:
			with open(stub_apk, 'rb') as src:
				xz_dump(src, out, 'manager_xz')

		flags += ' B_INIT=1'
		old_plat = True

	if 'magiskboot' in targets:
		flags += ' B_BOOT=1'
		old_plat = True

	if old_plat:
		proc = subprocess.run('{} -C native {} -j{}'.format(ndk_build, flags, cpu_count), shell=True, stdout=STDOUT)
		if proc.returncode != 0:
			error('Build binaries failed!')
		collect_binary()

	new_plat = False
	flags = base_flags

	if 'busybox' in targets:
		flags += ' B_BB=1'
		new_plat = True

	if new_plat:
		proc = subprocess.run('{} -C native NEW_PLAT=1 {} -j{}'.format(ndk_build, flags, cpu_count), shell=True, stdout=STDOUT)
		if proc.returncode != 0:
			error('Build binaries failed!')
		collect_binary()

def sign_apk(source, target):
	# Find the latest build tools
	build_tool = os.path.join(os.environ['ANDROID_HOME'], 'build-tools',
		sorted(os.listdir(os.path.join(os.environ['ANDROID_HOME'], 'build-tools')))[-1])

	proc = subprocess.run([os.path.join(build_tool, 'zipalign'), '-vpf', '4', source, target], stdout=subprocess.DEVNULL)
	if proc.returncode != 0:
		error('Zipalign Magisk Manager failed!')

	# Find apksigner.jar
	apksigner = ''
	for root, dirs, files in os.walk(build_tool):
		if 'apksigner.jar' in files:
			apksigner = os.path.join(root, 'apksigner.jar')
			break
	if not apksigner:
		error('Cannot find apksigner.jar in Android SDK build tools')

	proc = subprocess.run('java -jar {} sign --ks release-key.jks --ks-pass pass:{} --ks-key-alias {} --key-pass pass:{} {}'.format(
		apksigner, config['keyStorePass'], config['keyAlias'], config['keyPass'], target), shell=True)
	if proc.returncode != 0:
		error('Release sign Magisk Manager failed!')

def build_apk(args):
	header('* Building Magisk Manager')

	source = os.path.join('scripts', 'util_functions.sh')
	target = os.path.join('app', 'src', 'full', 'res', 'raw', 'util_functions.sh')
	cp(source, target)

	if args.release:
		if not os.path.exists('release-key.jks'):
			error('Please generate a java keystore and place it in \'release-key.jks\'')

		proc = subprocess.run('{} app:assembleRelease'.format(gradlew), shell=True, stdout=STDOUT)
		if proc.returncode != 0:
			error('Build Magisk Manager failed!')

		unsigned = os.path.join('app', 'build', 'outputs', 'apk', 'full', 'release', 'app-full-release-unsigned.apk')
		release = os.path.join(config['outdir'], 'app-release.apk')
		sign_apk(unsigned, release)
		header('Output: ' + release)
		rm(unsigned)

		unsigned = os.path.join('app', 'build', 'outputs', 'apk', 'stub', 'release', 'app-stub-release-unsigned.apk')
		release = os.path.join(config['outdir'], 'stub-release.apk')
		sign_apk(unsigned, release)
		header('Output: ' + release)
		rm(unsigned)
	else:
		proc = subprocess.run('{} app:assembleDebug'.format(gradlew), shell=True, stdout=STDOUT)
		if proc.returncode != 0:
			error('Build Magisk Manager failed!')

		source = os.path.join('app', 'build', 'outputs', 'apk', 'full', 'debug', 'app-full-debug.apk')
		target = os.path.join(config['outdir'], 'app-debug.apk')
		mv(source, target)
		header('Output: ' + target)

		source = os.path.join('app', 'build', 'outputs', 'apk', 'stub', 'debug', 'app-stub-debug.apk')
		target = os.path.join(config['outdir'], 'stub-debug.apk')
		mv(source, target)
		header('Output: ' + target)

def build_snet(args):
	proc = subprocess.run('{} snet:assembleRelease'.format(gradlew), shell=True, stdout=STDOUT)
	if proc.returncode != 0:
		error('Build snet extention failed!')
	source = os.path.join('snet', 'build', 'outputs', 'apk', 'release', 'snet-release-unsigned.apk')
	target = os.path.join(config['outdir'], 'snet.apk')
	# Re-compress the whole APK for smaller size
	with zipfile.ZipFile(target, 'w', compression=zipfile.ZIP_DEFLATED, allowZip64=False) as zout:
		with zipfile.ZipFile(source) as zin:
			for item in zin.infolist():
				zout.writestr(item.filename, zin.read(item))
	rm(source)
	header('Output: ' + target)

def xz_dump(src, out, var_name):
	out.write('const static unsigned char {}[] = {{'.format(var_name))
	for i, c in enumerate(lzma.compress(src.read(), preset=9)):
		if i % 16 == 0:
			out.write('\n')
		out.write('0x{:02X},'.format(c))
	out.write('\n};\n')
	out.flush()

def gen_update_binary():
	update_bin = []
	binary = os.path.join('native', 'out', 'armeabi-v7a', 'b64xz')
	if not os.path.exists(binary):
		error('Please build \'binary\' before zipping!')
	with open(binary, 'rb') as b64xz:
		update_bin.append('#! /sbin/sh\nEX_ARM=\'')
		update_bin.append(''.join("\\x{:02X}".format(c) for c in b64xz.read()))
	binary = os.path.join('native', 'out', 'x86', 'b64xz')
	with open(binary, 'rb') as b64xz:
		update_bin.append('\'\nEX_X86=\'')
		update_bin.append(''.join("\\x{:02X}".format(c) for c in b64xz.read()))
	binary = os.path.join('native', 'out', 'armeabi-v7a', 'busybox')
	with open(binary, 'rb') as busybox:
		update_bin.append('\'\nBB_ARM=')
		update_bin.append(base64.b64encode(lzma.compress(busybox.read(), preset=9)).decode('ascii'))
	binary = os.path.join('native', 'out', 'x86', 'busybox')
	with open(binary, 'rb') as busybox:
		update_bin.append('\nBB_X86=')
		update_bin.append(base64.b64encode(lzma.compress(busybox.read(), preset=9)).decode('ascii'))
		update_bin.append('\n')
	with open(os.path.join('scripts', 'update_binary.sh'), 'r') as script:
		update_bin.append(script.read())
	return ''.join(update_bin)

def zip_main(args):
	header('* Packing Flashable Zip')

	unsigned = tempfile.mkstemp()[1]

	with zipfile.ZipFile(unsigned, 'w', compression=zipfile.ZIP_DEFLATED, allowZip64=False) as zipf:
		# META-INF
		# update-binary
		target = os.path.join('META-INF', 'com', 'google', 'android', 'update-binary')
		print('zip: ' + target)
		zipf.writestr(target, gen_update_binary())
		# updater-script
		source = os.path.join('scripts', 'flash_script.sh')
		target = os.path.join('META-INF', 'com', 'google', 'android', 'updater-script')
		zip_with_msg(zipf, source, target)

		# Binaries
		for lib_dir, zip_dir in [('arm64-v8a', 'arm64'), ('armeabi-v7a', 'arm'), ('x86', 'x86'), ('x86_64', 'x64')]:
			for binary in ['magiskinit', 'magiskboot']:
				source = os.path.join('native', 'out', lib_dir, binary)
				target = os.path.join(zip_dir, binary)
				zip_with_msg(zipf, source, target)

		# APK
		source = os.path.join(config['outdir'], 'app-release.apk' if args.release else 'app-debug.apk')
		target = os.path.join('common', 'magisk.apk')
		zip_with_msg(zipf, source, target)

		# Scripts
		# boot_patch.sh
		source = os.path.join('scripts', 'boot_patch.sh')
		target = os.path.join('common', 'boot_patch.sh')
		zip_with_msg(zipf, source, target)
		# util_functions.sh
		source = os.path.join('scripts', 'util_functions.sh')
		with open(source, 'r') as script:
			# Add version info util_functions.sh
			util_func = script.read().replace('#MAGISK_VERSION_STUB',
				'MAGISK_VER="{}"\nMAGISK_VER_CODE={}'.format(config['version'], config['versionCode']))
			target = os.path.join('common', 'util_functions.sh')
			print('zip: ' + source + ' -> ' + target)
			zipf.writestr(target, util_func)
		# addon.d.sh
		source = os.path.join('scripts', 'addon.d.sh')
		target = os.path.join('common', 'addon.d.sh')
		zip_with_msg(zipf, source, target)

		# Prebuilts
		for chromeos in ['futility', 'kernel_data_key.vbprivk', 'kernel.keyblock']:
			source = os.path.join('chromeos', chromeos)
			zip_with_msg(zipf, source, source)

		# End of zipping

	output = os.path.join(config['outdir'], 'Magisk-v{}.zip'.format(config['version']) if config['prettyName'] else 
		'magisk-release.zip' if args.release else 'magisk-debug.zip')
	sign_adjust_zip(unsigned, output)
	header('Output: ' + output)

def zip_uninstaller(args):
	header('* Packing Uninstaller Zip')

	unsigned = tempfile.mkstemp()[1]

	with zipfile.ZipFile(unsigned, 'w', compression=zipfile.ZIP_DEFLATED, allowZip64=False) as zipf:
		# META-INF
		# update-binary
		target = os.path.join('META-INF', 'com', 'google', 'android', 'update-binary')
		print('zip: ' + target)
		zipf.writestr(target, gen_update_binary())
		# updater-script
		source = os.path.join('scripts', 'magisk_uninstaller.sh')
		target = os.path.join('META-INF', 'com', 'google', 'android', 'updater-script')
		zip_with_msg(zipf, source, target)

		# Binaries
		for lib_dir, zip_dir in [('arm64-v8a', 'arm64'), ('armeabi-v7a', 'arm'), ('x86', 'x86'), ('x86_64', 'x64')]:
			for bin in ['magisk', 'magiskboot']:
				source = os.path.join('native', 'out', lib_dir, bin)
				target = os.path.join(zip_dir, bin)
				zip_with_msg(zipf, source, target)

		# Scripts
		# util_functions.sh
		source = os.path.join('scripts', 'util_functions.sh')
		with open(source, 'r') as script:
			# Remove the stub
			target = os.path.join('util_functions.sh')
			print('zip: ' + source + ' -> ' + target)
			zipf.writestr(target, script.read())

		# Prebuilts
		for chromeos in ['futility', 'kernel_data_key.vbprivk', 'kernel.keyblock']:
			source = os.path.join('chromeos', chromeos)
			zip_with_msg(zipf, source, source)

		# End of zipping

	output = os.path.join(config['outdir'], 'Magisk-uninstaller-{}.zip'.format(datetime.datetime.now().strftime('%Y%m%d')) 
		if config['prettyName'] else 'magisk-uninstaller.zip')
	sign_adjust_zip(unsigned, output)
	header('Output: ' + output)

def sign_adjust_zip(unsigned, output):
	signer_name = 'zipsigner-2.2.jar'
	jarsigner = os.path.join('utils', 'build', 'libs', signer_name)

	if not os.path.exists(jarsigner):
		header('* Building ' + signer_name)
		proc = subprocess.run('{} utils:shadowJar'.format(gradlew), shell=True, stdout=STDOUT)
		if proc.returncode != 0:
			error('Build {} failed!'.format(signer_name))

	header('* Signing Zip')

	signed = tempfile.mkstemp()[1]

	proc = subprocess.run(['java', '-jar', jarsigner, unsigned, output])
	if proc.returncode != 0:
		error('Signing zip failed!')

def cleanup(args):
	if len(args.target) == 0:
		args.target = ['native', 'java']

	if 'native' in args.target:
		header('* Cleaning native')
		subprocess.run(ndk_build + ' -C native B_MAGISK=1 B_INIT=1 B_BOOT=1 B_BXZ=1 B_BB=1 clean', shell=True, stdout=STDOUT)
		shutil.rmtree(os.path.join('native', 'out'), ignore_errors=True)

	if 'java' in args.target:
		header('* Cleaning java')
		subprocess.run('{} app:clean snet:clean utils:clean'.format(os.path.join('.', 'gradlew')), shell=True, stdout=STDOUT)

def parse_config():
	c = {}
	with open('config.prop', 'r') as f:
		for line in [l.strip(' \t\r\n') for l in f]:
			if line.startswith('#') or len(line) == 0:
				continue
			prop = line.split('=')
			c[prop[0].strip(' \t\r\n')] = prop[1].strip(' \t\r\n')

	if 'version' not in c or 'versionCode' not in c:
		error('"version" and "versionCode" is required in "config.prop"')

	try:
		c['versionCode'] = int(c['versionCode'])
	except ValueError:
		error('"versionCode" is required to be an integer')

	if 'prettyName' not in c:
		c['prettyName'] = 'false'

	c['prettyName'] = c['prettyName'].lower() == 'true'

	if 'outdir' not in c:
		c['outdir'] = 'out'

	mkdir_p(c['outdir'])
	return c

config = parse_config()

parser = argparse.ArgumentParser(description='Magisk build script')
parser.add_argument('-r', '--release', action='store_true', help='compile Magisk for release')
parser.add_argument('-v', '--verbose', action='store_true', help='verbose output')
subparsers = parser.add_subparsers(title='actions')

all_parser = subparsers.add_parser('all', help='build everything (binaries/apks/zips)')
all_parser.set_defaults(func=build_all)

binary_parser = subparsers.add_parser('binary', help='build binaries. Target: magisk magiskinit magiskboot busybox b64xz')
binary_parser.add_argument('target', nargs='*')
binary_parser.set_defaults(func=build_binary)

apk_parser = subparsers.add_parser('apk', help='build Magisk Manager APK')
apk_parser.set_defaults(func=build_apk)

snet_parser = subparsers.add_parser('snet', help='build snet extention for Magisk Manager')
snet_parser.set_defaults(func=build_snet)

zip_parser = subparsers.add_parser('zip', help='zip and sign Magisk into a flashable zip')
zip_parser.set_defaults(func=zip_main)

uninstaller_parser = subparsers.add_parser('uninstaller', help='create flashable uninstaller')
uninstaller_parser.set_defaults(func=zip_uninstaller)

clean_parser = subparsers.add_parser('clean', help='cleanup. Target: native java')
clean_parser.add_argument('target', nargs='*')
clean_parser.set_defaults(func=cleanup)

if len(sys.argv) == 1:
	parser.print_help()
	sys.exit(1)

args = parser.parse_args()
STDOUT = None if args.verbose else subprocess.DEVNULL
args.func(args)
