/* socket.c - All socket related operations
 */

#include <fcntl.h>
#include <endian.h>

#include "daemon.h"
#include "logging.h"
#include "utils.h"
#include "magisk.h"

#define ABS_SOCKET_LEN(sun) (sizeof(sun->sun_family) + strlen(sun->sun_path + 1) + 1)

socklen_t setup_sockaddr(struct sockaddr_un *sun, daemon_t d) {
	memset(sun, 0, sizeof(*sun));
	sun->sun_family = AF_LOCAL;
	const char *name;
	switch (d) {
		case MAIN_DAEMON:
			name = MAIN_SOCKET;
			break;
		case LOG_DAEMON:
			name = LOG_SOCKET;
			break;
	}
	strcpy(sun->sun_path + 1, name);
	return ABS_SOCKET_LEN(sun);
}

int create_rand_socket(struct sockaddr_un *sun) {
	memset(sun, 0, sizeof(*sun));
	sun->sun_family = AF_LOCAL;
	gen_rand_str(sun->sun_path + 1, 9);
	int fd = xsocket(AF_LOCAL, SOCK_STREAM | SOCK_CLOEXEC, 0);
	xbind(fd, (struct sockaddr*) sun, ABS_SOCKET_LEN(sun));
	xlisten(fd, 1);
	return fd;
}

int socket_accept(int serv_fd, int timeout) {
	struct timeval tv;
	fd_set fds;
	int rc;

	tv.tv_sec = timeout;
	tv.tv_usec = 0;
	FD_ZERO(&fds);
	FD_SET(serv_fd, &fds);
	do {
		rc = select(serv_fd + 1, &fds, NULL, NULL, &tv);
	} while (rc < 0 && errno == EINTR);
	if (rc < 1) {
		PLOGE("select");
		exit(-1);
	}

	return xaccept4(serv_fd, NULL, NULL, SOCK_CLOEXEC);
}

/*
 * Receive a file descriptor from a Unix socket.
 * Contributed by @mkasick
 *
 * Returns the file descriptor on success, or -1 if a file
 * descriptor was not actually included in the message
 *
 * On error the function terminates by calling exit(-1)
 */
int recv_fd(int sockfd) {
	// Need to receive data from the message, otherwise don't care about it.
	char iovbuf;

	struct iovec iov = {
		.iov_base = &iovbuf,
		.iov_len  = 1,
	};

	char cmsgbuf[CMSG_SPACE(sizeof(int))];

	struct msghdr msg = {
		.msg_iov        = &iov,
		.msg_iovlen     = 1,
		.msg_control    = cmsgbuf,
		.msg_controllen = sizeof(cmsgbuf),
	};

	xrecvmsg(sockfd, &msg, MSG_WAITALL);

	// Was a control message actually sent?
	switch (msg.msg_controllen) {
	case 0:
		// No, so the file descriptor was closed and won't be used.
		return -1;
	case sizeof(cmsgbuf):
		// Yes, grab the file descriptor from it.
		break;
	default:
		goto error;
	}

	struct cmsghdr *cmsg = CMSG_FIRSTHDR(&msg);

	if (cmsg             == NULL                  ||
		cmsg->cmsg_len   != CMSG_LEN(sizeof(int)) ||
		cmsg->cmsg_level != SOL_SOCKET            ||
		cmsg->cmsg_type  != SCM_RIGHTS) {
error:
		LOGE("unable to read fd");
		exit(-1);
	}

	return *(int *)CMSG_DATA(cmsg);
}

/*
 * Send a file descriptor through a Unix socket.
 * Contributed by @mkasick
 *
 * On error the function terminates by calling exit(-1)
 *
 * fd may be -1, in which case the dummy data is sent,
 * but no control message with the FD is sent.
 */
void send_fd(int sockfd, int fd) {
	// Need to send some data in the message, this will do.
	struct iovec iov = {
		.iov_base = "",
		.iov_len  = 1,
	};

	struct msghdr msg = {
		.msg_iov        = &iov,
		.msg_iovlen     = 1,
	};

	char cmsgbuf[CMSG_SPACE(sizeof(int))];

	if (fd != -1) {
		// Is the file descriptor actually open?
		if (fcntl(fd, F_GETFD) == -1) {
			if (errno != EBADF) {
				PLOGE("unable to send fd");
			}
			// It's closed, don't send a control message or sendmsg will EBADF.
		} else {
			// It's open, send the file descriptor in a control message.
			msg.msg_control    = cmsgbuf;
			msg.msg_controllen = sizeof(cmsgbuf);

			struct cmsghdr *cmsg = CMSG_FIRSTHDR(&msg);

			cmsg->cmsg_len   = CMSG_LEN(sizeof(int));
			cmsg->cmsg_level = SOL_SOCKET;
			cmsg->cmsg_type  = SCM_RIGHTS;

			*(int *)CMSG_DATA(cmsg) = fd;
		}
	}

	xsendmsg(sockfd, &msg, 0);
}

int read_int(int fd) {
	int val;
	xxread(fd, &val, sizeof(int));
	return val;
}

int read_int_be(int fd) {
	uint32_t val;
	xxread(fd, &val, sizeof(val));
	return ntohl(val);
}

void write_int(int fd, int val) {
	if (fd < 0) return;
	xwrite(fd, &val, sizeof(int));
}

void write_int_be(int fd, int val) {
	uint32_t nl = htonl(val);
	xwrite(fd, &nl, sizeof(nl));
}

static char *rd_str(int fd, int len) {
	char* val = xmalloc(sizeof(char) * (len + 1));
	xxread(fd, val, len);
	val[len] = '\0';
	return val;
}

char* read_string(int fd) {
	int len = read_int(fd);
	return rd_str(fd, len);
}

char* read_string_be(int fd) {
	int len = read_int_be(fd);
	return rd_str(fd, len);
}

void write_string(int fd, const char *val) {
	if (fd < 0) return;
	int len = strlen(val);
	write_int(fd, len);
	xwrite(fd, val, len);
}

void write_string_be(int fd, const char *val) {
	int len = strlen(val);
	write_int_be(fd, len);
	xwrite(fd, val, len);
}

void write_key_value(int fd, const char *key, const char *val) {
	write_string_be(fd, key);
	write_string_be(fd, val);
}

void write_key_token(int fd, const char *key, int tok) {
	char val[16];
	sprintf(val, "%d", tok);
	write_key_value(fd, key, val);
}
