#include <jni.h>
#include <stdint.h>
#include "ZeroTierSockets.h"

#define MINECRAFT_BUFFER_SIZE 16384

static uint64_t minecraft_network;
static int joined;

static int tcp_socket(void)
{
    int fd = zts_socket(ZTS_AF_INET, ZTS_SOCK_STREAM, 0);
    if (fd >= 0) {
        zts_set_no_delay(fd, 1);
        zts_set_keepalive(fd, 1);
    }
    return fd;
}

JNIEXPORT jint JNICALL Java_link_dabois_EmbeddedZeroTier_startNode(
        JNIEnv *env, jclass type, jstring directory, jlong network)
{
    if (minecraft_network != 0 || network == 0) {
        return -1;
    }
    const char *path = (*env)->GetStringUTFChars(env, directory, 0);
    if (!path) {
        return -1;
    }
    int status = zts_init_from_storage(path);
    (*env)->ReleaseStringUTFChars(env, directory, path);
    if (status != ZTS_ERR_OK
            || zts_init_allow_net_cache(0) != ZTS_ERR_OK
            || zts_init_allow_port_mapping(0) != ZTS_ERR_OK
            || zts_init_set_random_port_range(20000, 59999) != ZTS_ERR_OK) {
        return -1;
    }
    minecraft_network = (uint64_t)network;
    return zts_node_start();
}

JNIEXPORT jlong JNICALL Java_link_dabois_EmbeddedZeroTier_nodeId(JNIEnv *env, jclass type)
{
    return (jlong)zts_node_get_id();
}

JNIEXPORT jboolean JNICALL Java_link_dabois_EmbeddedZeroTier_networkReady(JNIEnv *env, jclass type)
{
    if (!zts_node_is_online()) {
        return JNI_FALSE;
    }
    if (!joined) {
        joined = zts_net_join(minecraft_network) == ZTS_ERR_OK;
    }
    return joined && zts_net_transport_is_ready(minecraft_network)
            && zts_addr_is_assigned(minecraft_network, ZTS_AF_INET) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL Java_link_dabois_EmbeddedZeroTier_open(
        JNIEnv *env, jclass type, jstring address, jint port, jint timeout)
{
    int fd = tcp_socket();
    if (fd < 0) {
        return fd;
    }
    const char *host = (*env)->GetStringUTFChars(env, address, 0);
    if (!host) {
        zts_close(fd);
        return -1;
    }
    struct zts_sockaddr_storage destination = {0};
    zts_socklen_t size = sizeof(destination);
    int result = zts_util_ipstr_to_saddr(host, (unsigned short)port, (struct zts_sockaddr *)&destination, &size);
    (*env)->ReleaseStringUTFChars(env, address, host);
    if (result != ZTS_ERR_OK || zts_set_blocking(fd, 0) != ZTS_ERR_OK) {
        zts_close(fd);
        return -1;
    }
    if (zts_bsd_connect(fd, (struct zts_sockaddr *)&destination, size) != ZTS_ERR_OK) {
        struct zts_pollfd poll_socket = { fd, ZTS_POLLOUT, 0 };
        int socket_error = 0;
        zts_socklen_t error_size = sizeof(socket_error);
        if (zts_bsd_poll(&poll_socket, 1, timeout) != 1 || !(poll_socket.revents & ZTS_POLLOUT)
                || zts_bsd_getsockopt(fd, ZTS_SOL_SOCKET, ZTS_SO_ERROR, &socket_error, &error_size) != ZTS_ERR_OK
                || socket_error != 0) {
            zts_close(fd);
            return -1;
        }
    }
    if (zts_set_blocking(fd, 1) != ZTS_ERR_OK) {
        zts_close(fd);
        return -1;
    }
    return fd;
}

JNIEXPORT jint JNICALL Java_link_dabois_EmbeddedZeroTier_openListener(JNIEnv *env, jclass type, jint port)
{
    char local[ZTS_INET6_ADDRSTRLEN];
    if (zts_addr_get_str(minecraft_network, ZTS_AF_INET, local, sizeof(local)) != ZTS_ERR_OK) {
        return -1;
    }
    int fd = zts_socket(ZTS_AF_INET, ZTS_SOCK_STREAM, 0);
    if (fd < 0) {
        return fd;
    }
    if (zts_bind(fd, local, (unsigned short)port) != ZTS_ERR_OK || zts_listen(fd, 16) != ZTS_ERR_OK) {
        zts_close(fd);
        return -1;
    }
    return fd;
}

JNIEXPORT jint JNICALL Java_link_dabois_EmbeddedZeroTier_acceptSocket(
        JNIEnv *env, jclass type, jint listener)
{
    struct zts_pollfd poll_socket = { listener, ZTS_POLLIN, 0 };
    int status = zts_bsd_poll(&poll_socket, 1, 250);
    if (status <= 0) {
        return status == 0 ? -2 : -1;
    }
    if (!(poll_socket.revents & ZTS_POLLIN)) {
        return -1;
    }
    int fd = zts_bsd_accept(listener, 0, 0);
    if (fd >= 0) {
        zts_set_no_delay(fd, 1);
        zts_set_keepalive(fd, 1);
    }
    return fd < 0 ? -1 : fd;
}

JNIEXPORT jint JNICALL Java_link_dabois_EmbeddedZeroTier_readSocket(
        JNIEnv *env, jclass type, jint fd, jbyteArray destination)
{
    jbyte buffer[MINECRAFT_BUFFER_SIZE];
    jsize length = (*env)->GetArrayLength(env, destination);
    if (length < 1 || length > MINECRAFT_BUFFER_SIZE) {
        return -1;
    }
    jint count = (jint)zts_read(fd, buffer, (size_t)length);
    if (count > 0) {
        (*env)->SetByteArrayRegion(env, destination, 0, count, buffer);
    }
    return count;
}

JNIEXPORT jint JNICALL Java_link_dabois_EmbeddedZeroTier_writeSocket(
        JNIEnv *env, jclass type, jint fd, jbyteArray source, jint offset, jint length)
{
    jbyte buffer[MINECRAFT_BUFFER_SIZE];
    jsize available = (*env)->GetArrayLength(env, source);
    if (offset < 0 || length < 1 || length > MINECRAFT_BUFFER_SIZE
            || offset > available || length > available - offset) {
        return -1;
    }
    (*env)->GetByteArrayRegion(env, source, offset, length, buffer);
    if ((*env)->ExceptionCheck(env)) {
        return -1;
    }
    return (jint)zts_write(fd, buffer, (size_t)length);
}

JNIEXPORT void JNICALL Java_link_dabois_EmbeddedZeroTier_shutdownSocket(
        JNIEnv *env, jclass type, jint fd)
{
    zts_shutdown_rdwr(fd);
}

JNIEXPORT void JNICALL Java_link_dabois_EmbeddedZeroTier_closeSocket(JNIEnv *env, jclass type, jint fd)
{
    zts_close(fd);
}
