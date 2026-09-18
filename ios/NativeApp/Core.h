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
