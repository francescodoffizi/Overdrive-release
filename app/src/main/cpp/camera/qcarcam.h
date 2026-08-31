#ifndef QCARCAM_H
#define QCARCAM_H

#include "qcarcam_types.h"

#ifdef __cplusplus
extern "C" {
#endif

qcarcam_ret_t qcarcam_initialize(qcarcam_init_t* p_init_params);
qcarcam_ret_t qcarcam_uninitialize(void);

qcarcam_ret_t qcarcam_query_inputs(qcarcam_input_identifier_t* p_inputs, uint32_t size, uint32_t* p_ret_size);

qcarcam_hndl_t qcarcam_open(qcarcam_input_t input_id);
qcarcam_ret_t qcarcam_close(qcarcam_hndl_t hndl);

qcarcam_ret_t qcarcam_g_param(qcarcam_hndl_t hndl, qcarcam_param_t param, qcarcam_param_value_t* p_value);
qcarcam_ret_t qcarcam_s_param(qcarcam_hndl_t hndl, qcarcam_param_t param, const qcarcam_param_value_t* p_value);

qcarcam_ret_t qcarcam_s_buffers(qcarcam_hndl_t hndl, qcarcam_buffers_t* p_buffers);

qcarcam_ret_t qcarcam_start(qcarcam_hndl_t hndl);
qcarcam_ret_t qcarcam_stop(qcarcam_hndl_t hndl);
qcarcam_ret_t qcarcam_pause(qcarcam_hndl_t hndl);
qcarcam_ret_t qcarcam_resume(qcarcam_hndl_t hndl);

qcarcam_ret_t qcarcam_get_frame(qcarcam_hndl_t hndl, qcarcam_frame_info_t* p_frame_info, uint64_t timeout, uint32_t flags);
qcarcam_ret_t qcarcam_release_frame(qcarcam_hndl_t hndl, uint32_t idx);

#ifdef __cplusplus
}
#endif

#endif // QCARCAM_H
