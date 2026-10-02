#include <stdbool.h>
#include <stdint.h>
#include <stddef.h>
void *ts_create(void);
void ts_destroy(void *handle);
int32_t ts_command(void *handle, const char *text);
char *ts_poll(void *handle);
void ts_free(char *text);
int32_t ts_capture(void *handle, const int16_t *samples, size_t count);
size_t ts_playback(void *handle, float *samples, size_t capacity);
void ts_set_notifier(void *handle, void (*callback)(size_t), size_t context);

// 48 kHz float PCM; retained independently of the session bridge.
const void *ts_playback_acquire(void *handle);
void ts_playback_release(const void *playback);
void ts_playback_active(const void *playback, bool active);
size_t ts_render(const void *playback, float *left, float *right, size_t frames, size_t stride);
uint64_t ts_render_count(const void *playback);
