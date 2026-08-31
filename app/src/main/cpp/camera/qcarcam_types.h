#ifndef QCARCAM_TYPES_H
#define QCARCAM_TYPES_H

#include <stdint.h>
#include <stddef.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef void* qcarcam_hndl_t;
typedef uint32_t qcarcam_input_t;

#define QCARCAM_MAX_NUM_PLANES 3
#define QCARCAM_MAX_NUM_BUFFERS 12
#define QCARCAM_TIMEOUT_INIFINITE 0xFFFFFFFFFFFFFFFFULL

typedef enum {
    QCARCAM_RET_OK = 0,
    QCARCAM_RET_FAILED = 1,
    QCARCAM_RET_BADPARAM = 2,
    QCARCAM_RET_BADSTATE = 3,
    QCARCAM_RET_NOMEM = 4,
    QCARCAM_RET_UNSUPPORTED = 5,
    QCARCAM_RET_TIMEOUT = 6,
    QCARCAM_RET_BUSY = 7,
} qcarcam_ret_t;

typedef enum {
    QCARCAM_INPUT_TYPE_EXT_REAR = 0,
    QCARCAM_INPUT_TYPE_EXT_FRONT = 1,
    QCARCAM_INPUT_TYPE_EXT_LEFT = 2,
    QCARCAM_INPUT_TYPE_EXT_RIGHT = 3,
    QCARCAM_INPUT_TYPE_EXT_SURROUND_ALL = 4,
    QCARCAM_INPUT_TYPE_MAX
} qcarcam_input_desc_t;

typedef enum {
    QCARCAM_EVENT_FRAME_READY = 1,
    QCARCAM_EVENT_INPUT_SIGNAL = 2,
    QCARCAM_EVENT_ERROR = 3,
    QCARCAM_EVENT_VENDOR = 4,
    QCARCAM_EVENT_MAX
} qcarcam_event_t;

typedef enum {
    QCARCAM_FMT_UYVY_8 = 0x10800000,
    QCARCAM_FMT_NV12 = 0x10800001,
    QCARCAM_FMT_RGB_888 = 0x10800002,
} qcarcam_color_fmt_t;

typedef struct {
    uint32_t width;
    uint32_t height;
    uint32_t stride;
    uint32_t size;
    void* p_buf;
} qcarcam_plane_t;

typedef struct {
    uint32_t flags;
    qcarcam_plane_t planes[QCARCAM_MAX_NUM_PLANES];
    uint32_t num_planes;
} qcarcam_buffer_t;

typedef struct {
    qcarcam_color_fmt_t color_fmt;
    uint32_t num_buffers;
    qcarcam_buffer_t* buffers;
    uint32_t flags;
} qcarcam_buffers_t;

typedef struct {
    int idx;
    uint32_t seq_no;
    uint64_t timestamp;
    uint64_t timestamp_system;
    uint64_t sof_qtimestamp;
    uint32_t field_type;
    uint32_t flags;
} qcarcam_frame_info_t;

typedef enum {
    QCARCAM_PARAM_EVENT_CB = 1,
    QCARCAM_PARAM_EVENT_MASK = 2,
    QCARCAM_PARAM_COLOR_FMT = 3,
    QCARCAM_PARAM_RESOLUTION = 4,
    QCARCAM_PARAM_BRIGHTNESS = 5,
    QCARCAM_PARAM_CONTRAST = 6,
    QCARCAM_PARAM_FRAME_RATE = 7,
    QCARCAM_PARAM_MAX
} qcarcam_param_t;

typedef union {
    void* ptr_value;
    uint32_t uint_value;
    int32_t int_value;
    float float_value;
    struct {
        uint32_t width;
        uint32_t height;
        float fps;
    } res_value;
} qcarcam_param_value_t;

typedef struct {
    uint32_t event_mask;
    void* p_user_data;
} qcarcam_event_payload_t;

typedef void (*qcarcam_event_cb_t)(qcarcam_hndl_t hndl, qcarcam_event_t event_id, qcarcam_event_payload_t* p_payload);

typedef struct {
    uint32_t flags;
} qcarcam_init_t;

typedef struct {
    qcarcam_input_desc_t desc;
    qcarcam_input_t input_id;
    uint32_t flags;
    char name[80];
} qcarcam_input_identifier_t;

#ifdef __cplusplus
}
#endif

#endif // QCARCAM_TYPES_H
