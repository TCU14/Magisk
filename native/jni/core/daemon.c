/* daemon.c - Magisk Daemon
 *
 * Start the daemon and wait for requests
 * Connect the daemon and send requests through sockets
 */

#include <stdlib.h>
#include <unistd.h>
#include <fcntl.h>
#include <string.h>
#include <pthread.h>
#include <signal.h>
#include <sys/un.h>
#include <sys/types.h>
#include <sys/mount.h>
#include <selinux/selinux.h>

#include "magisk.h"
#include "utils.h"
#include "daemon.h"
#include "resetprop.h"
#include "magiskpolicy.h"

int setup_done = 0;
int seperate_vendor = 0;
int full_patch_pid = -1;

static void *request_handler(void *args) {
	int client = *((int *) args);
	free(args);
	int req = read_int(client);

	struct ucred credential;
	get_client_cred(client, &credential);

	switch (req) {
	case LAUNCH_MAGISKHIDE:
	case STOP_MAGISKHIDE:
	case ADD_HIDELIST:
	case RM_HIDELIST:
	case LS_HIDELIST:
	case POST_FS_DATA:
	case LATE_START:
		if (credential.uid != 0) {
			write_int(client, ROOT_REQUIRED);
			close(client);
			return NULL;
		}
	default:
		break;
	}

	switch (req) {
	case LAUNCH_MAGISKHIDE:
		launch_magiskhide(client);
		break;
	case STOP_MAGISKHIDE:
		stop_magiskhide(client);
		break;
	case ADD_HIDELIST:
		add_hide_list(client);
		break;
	case RM_HIDELIST:
		rm_hide_list(client);
		break;
	case LS_HIDELIST:
		ls_hide_list(client);
		break;
	case SUPERUSER:
		su_daemon_receiver(client, &credential);
		break;
	case CHECK_VERSION:
		write_string(client, MAGISK_VER_STR);
		close(client);
		break;
	case CHECK_VERSION_CODE:
		write_int(client, MAGISK_VER_CODE);
		close(client);
		break;
	case POST_FS_DATA:
		post_fs_data(client);
		break;
	case LATE_START:
		late_start(client);
		break;
	default:
		break;
	}
	return NULL;
}


static void *start_magisk_hide(void *args) {
	launch_magiskhide(-1);
	return NULL;
}

void auto_start_magiskhide() {
	char *hide_prop = getprop2(MAGISKHIDE_PROP, 1);
	if (hide_prop == NULL || strcmp(hide_prop, "0") != 0) {
		pthread_t thread;
		xpthread_create(&thread, NULL, start_magisk_hide, NULL);
		pthread_detach(thread);
	}
	free(hide_prop);
}

void start_daemon() {
	setsid();
	setcon("u:r:"SEPOL_PROC_DOMAIN":s0");
	int fd = xopen("/dev/null", O_RDWR | O_CLOEXEC);
	xdup2(fd, STDIN_FILENO);
	xdup2(fd, STDOUT_FILENO);
	xdup2(fd, STDERR_FILENO);
	close(fd);

	// Block user signals
	sigset_t block_set;
	sigemptyset(&block_set);
	sigaddset(&block_set, SIGUSR1);
	sigaddset(&block_set, SIGUSR2);
	pthread_sigmask(SIG_SETMASK, &block_set, NULL);

	struct sockaddr_un sun;
	fd = setup_socket(&sun);

	if (xbind(fd, (struct sockaddr*) &sun, sizeof(sun)))
		exit(1);
	xlisten(fd, 10);

	// Start the log monitor
	monitor_logs();

	LOGI("Magisk v" xstr(MAGISK_VERSION) "(" xstr(MAGISK_VER_CODE) ") daemon started\n");

	// Change process name
	strcpy(argv0, "magiskd");

	// Loop forever to listen for requests
	while(1) {
		int *client = xmalloc(sizeof(int));
		*client = xaccept4(fd, NULL, NULL, SOCK_CLOEXEC);
		pthread_t thread;
		xpthread_create(&thread, NULL, request_handler, client);
		// Detach the thread, we will never join it
		pthread_detach(thread);
	}
}

/* Connect the daemon, and return a socketfd */
int connect_daemon() {
	struct sockaddr_un sun;
	int fd = setup_socket(&sun);
	if (connect(fd, (struct sockaddr*) &sun, sizeof(sun))) {
		// If we cannot access the daemon, we start a daemon in the child process if possible

		if (getuid() != UID_ROOT || getgid() != UID_ROOT) {
			fprintf(stderr, "No daemon is currently running!\n");
			exit(1);
		}

		if (fork_dont_care() == 0) {
			LOGD("client: connect fail, try launching new daemon process\n");
			close(fd);
			start_daemon();
		}

		while (connect(fd, (struct sockaddr*) &sun, sizeof(sun)))
			usleep(10000);
	}
	return fd;
}
