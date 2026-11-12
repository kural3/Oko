// Camera2PC — источник виртуальной камеры для Windows 11 (Media Foundation Virtual Camera).
// DLL загружается службой Windows Frame Server. Кадры берутся из разделяемой памяти,
// куда их пишет программа Camera2PC на ПК.

#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <mfapi.h>
#include <mfidl.h>
#include <mferror.h>
#include <mftransform.h>
#include <sddl.h>
#include <propvarutil.h>
#include <mmsystem.h>
#include <vector>
#include <string.h>

#include "shared.h"

// ---------------------------------------------------------------------------
// Определения, которых нет в заголовках mingw (значения сверены с Windows SDK 10.0.28000)
// ---------------------------------------------------------------------------
static const GUID IID_IMFMediaSource2_X = {0xfbb03414, 0xd13b, 0x4786, {0x83, 0x19, 0x5a, 0xc5, 0x1f, 0xc0, 0xa1, 0x36}};
static const GUID IID_IMFMediaSourceEx_X = {0x3c9b2eb9, 0x86d5, 0x4514, {0xa3, 0x94, 0xf5, 0x66, 0x64, 0xf9, 0xf0, 0xd8}};
static const GUID IID_IMFMediaStream2_X = {0xc5bc37d6, 0x75c7, 0x46a1, {0xa1, 0x32, 0x81, 0xb5, 0xf7, 0x23, 0xc2, 0x0f}};
static const GUID IID_IMFSampleAllocatorControl_X = {0xda62b958, 0x3a38, 0x4a97, {0xbd, 0x27, 0x14, 0x9c, 0x64, 0x0c, 0x07, 0x71}};
static const GUID IID_IKsControl_X = {0x28f54685, 0x06fd, 0x11d2, {0xb2, 0x7a, 0x00, 0xa0, 0xc9, 0x22, 0x31, 0x96}};

static const GUID MF_DEVICESTREAM_STREAM_ID_X = {0x11bd5120, 0xd124, 0x446b, {0x88, 0xe6, 0x17, 0x06, 0x02, 0x57, 0xff, 0xf9}};
static const GUID MF_DEVICESTREAM_STREAM_CATEGORY_X = {0x2939e7b8, 0xa62e, 0x4579, {0xb6, 0x74, 0xd4, 0x07, 0x3d, 0xfa, 0xbb, 0xba}};
static const GUID MF_DEVICESTREAM_FRAMESERVER_SHARED_X = {0x1cb378e9, 0xb279, 0x41d4, {0xaf, 0x97, 0x34, 0xa2, 0x43, 0xe6, 0x83, 0x20}};
static const GUID MF_DEVICESTREAM_ATTRIBUTE_FRAMESOURCE_TYPES_X = {0x17145fd1, 0x1b2b, 0x423c, {0x80, 0x01, 0x2b, 0x68, 0x33, 0xed, 0x35, 0x88}};
static const GUID MF_VIRTUALCAMERA_PROVIDE_ASSOCIATED_CAMERA_SOURCES_X = {0xf0273718, 0x4a4d, 0x4ac5, {0xa1, 0x5d, 0x30, 0x5e, 0xb5, 0xe9, 0x06, 0x67}};
static const GUID MFT_TRANSFORM_CLSID_Attribute_X = {0x6821c42b, 0x65a4, 0x4e82, {0x99, 0xbc, 0x9a, 0x88, 0x20, 0x5e, 0xcd, 0x0c}};
static const GUID PINNAME_VIDEO_CAPTURE_X = {0xfb6c4281, 0x0353, 0x11d1, {0x90, 0x5f, 0x00, 0x00, 0xc0, 0xcc, 0x16, 0xba}};
static const UINT32 MFFrameSourceTypes_Color_X = 1;

struct IMFMediaSource2X : public IMFMediaSourceEx {
    virtual HRESULT STDMETHODCALLTYPE SetMediaType(DWORD dwStreamID, IMFMediaType* pMediaType) = 0;
};

struct IMFMediaStream2X : public IMFMediaStream {
    virtual HRESULT STDMETHODCALLTYPE SetStreamState(MF_STREAM_STATE value) = 0;
    virtual HRESULT STDMETHODCALLTYPE GetStreamState(MF_STREAM_STATE* value) = 0;
};

enum MFSampleAllocatorUsageX {
    MFSampleAllocatorUsage_UsesProvidedAllocator_X = 0,
    MFSampleAllocatorUsage_UsesCustomAllocator_X = 1,
    MFSampleAllocatorUsage_DoesNotAllocate_X = 2,
};

struct IMFSampleAllocatorControlX : public IUnknown {
    virtual HRESULT STDMETHODCALLTYPE SetDefaultAllocator(DWORD dwOutputStreamID, IUnknown* pAllocator) = 0;
    virtual HRESULT STDMETHODCALLTYPE GetAllocatorUsage(DWORD dwOutputStreamID, DWORD* pdwInputStreamID,
                                                        MFSampleAllocatorUsageX* peUsage) = 0;
};

struct IKsControlX : public IUnknown {
    virtual LONG STDMETHODCALLTYPE KsProperty(void* Property, ULONG PropertyLength, void* PropertyData,
                                              ULONG DataLength, ULONG* BytesReturned) = 0;
    virtual LONG STDMETHODCALLTYPE KsMethod(void* Method, ULONG MethodLength, void* MethodData,
                                            ULONG DataLength, ULONG* BytesReturned) = 0;
    virtual LONG STDMETHODCALLTYPE KsEvent(void* Event, ULONG EventLength, void* EventData,
                                           ULONG DataLength, ULONG* BytesReturned) = 0;
};

// ---------------------------------------------------------------------------
// Вспомогательное
// ---------------------------------------------------------------------------
static HMODULE g_module;
static volatile LONG g_objects;
static volatile LONG g_locks;

template <class T> static void SafeRelease(T*& p) {
    if (p) { p->Release(); p = nullptr; }
}

struct Lock {
    CRITICAL_SECTION* cs;
    explicit Lock(CRITICAL_SECTION* c) : cs(c) { EnterCriticalSection(cs); }
    ~Lock() { LeaveCriticalSection(cs); }
};

#define KS_NOT_FOUND ((LONG)HRESULT_FROM_WIN32(ERROR_SET_NOT_FOUND))

// Делегирование всех методов IMFAttributes во внутренний объект A
#define DELEGATE_IMFATTRIBUTES(A)                                                                          \
    STDMETHODIMP GetItem(REFGUID k, PROPVARIANT* v) override { return A->GetItem(k, v); }                  \
    STDMETHODIMP GetItemType(REFGUID k, MF_ATTRIBUTE_TYPE* t) override { return A->GetItemType(k, t); }    \
    STDMETHODIMP CompareItem(REFGUID k, REFPROPVARIANT v, BOOL* r) override { return A->CompareItem(k, v, r); } \
    STDMETHODIMP Compare(IMFAttributes* o, MF_ATTRIBUTES_MATCH_TYPE m, BOOL* r) override { return A->Compare(o, m, r); } \
    STDMETHODIMP GetUINT32(REFGUID k, UINT32* v) override { return A->GetUINT32(k, v); }                   \
    STDMETHODIMP GetUINT64(REFGUID k, UINT64* v) override { return A->GetUINT64(k, v); }                   \
    STDMETHODIMP GetDouble(REFGUID k, double* v) override { return A->GetDouble(k, v); }                   \
    STDMETHODIMP GetGUID(REFGUID k, GUID* v) override { return A->GetGUID(k, v); }                         \
    STDMETHODIMP GetStringLength(REFGUID k, UINT32* l) override { return A->GetStringLength(k, l); }       \
    STDMETHODIMP GetString(REFGUID k, LPWSTR s, UINT32 n, UINT32* l) override { return A->GetString(k, s, n, l); } \
    STDMETHODIMP GetAllocatedString(REFGUID k, LPWSTR* s, UINT32* l) override { return A->GetAllocatedString(k, s, l); } \
    STDMETHODIMP GetBlobSize(REFGUID k, UINT32* n) override { return A->GetBlobSize(k, n); }               \
    STDMETHODIMP GetBlob(REFGUID k, UINT8* b, UINT32 n, UINT32* s) override { return A->GetBlob(k, b, n, s); } \
    STDMETHODIMP GetAllocatedBlob(REFGUID k, UINT8** b, UINT32* s) override { return A->GetAllocatedBlob(k, b, s); } \
    STDMETHODIMP GetUnknown(REFGUID k, REFIID r, LPVOID* p) override { return A->GetUnknown(k, r, p); }    \
    STDMETHODIMP SetItem(REFGUID k, REFPROPVARIANT v) override { return A->SetItem(k, v); }                \
    STDMETHODIMP DeleteItem(REFGUID k) override { return A->DeleteItem(k); }                               \
    STDMETHODIMP DeleteAllItems() override { return A->DeleteAllItems(); }                                 \
    STDMETHODIMP SetUINT32(REFGUID k, UINT32 v) override { return A->SetUINT32(k, v); }                    \
    STDMETHODIMP SetUINT64(REFGUID k, UINT64 v) override { return A->SetUINT64(k, v); }                    \
    STDMETHODIMP SetDouble(REFGUID k, double v) override { return A->SetDouble(k, v); }                    \
    STDMETHODIMP SetGUID(REFGUID k, REFGUID v) override { return A->SetGUID(k, v); }                       \
    STDMETHODIMP SetString(REFGUID k, LPCWSTR v) override { return A->SetString(k, v); }                   \
    STDMETHODIMP SetBlob(REFGUID k, const UINT8* b, UINT32 n) override { return A->SetBlob(k, b, n); }     \
    STDMETHODIMP SetUnknown(REFGUID k, IUnknown* u) override { return A->SetUnknown(k, u); }               \
    STDMETHODIMP LockStore() override { return A->LockStore(); }                                          \
    STDMETHODIMP UnlockStore() override { return A->UnlockStore(); }                                      \
    STDMETHODIMP GetCount(UINT32* c) override { return A->GetCount(c); }                                   \
    STDMETHODIMP GetItemByIndex(UINT32 i, GUID* k, PROPVARIANT* v) override { return A->GetItemByIndex(i, k, v); } \
    STDMETHODIMP CopyAllItems(IMFAttributes* d) override { return A->CopyAllItems(d); }

// ---------------------------------------------------------------------------
// Чтение кадров из разделяемой памяти
// ---------------------------------------------------------------------------
class FrameReader {
public:
    ~FrameReader() {
        if (view_) UnmapViewOfFile(view_);
        if (map_) CloseHandle(map_);
    }

    // Возвращает true, если есть свежий кадр (в src/w/h/bpp).
    bool Read(const uint8_t*& src, uint32_t& w, uint32_t& h, uint32_t& bpp) {
        if (!Ensure()) return false;
        auto* hdr = reinterpret_cast<volatile Camera2PCHeader*>(view_);
        hdr->consumerTick = GetTickCount64();  // программа на ПК видит, что камеру смотрят
        if (hdr->magic != CAMERA2PC_MAGIC) return false;
        uint64_t tick = hdr->tick;
        uint64_t now = GetTickCount64();
        if (tick == 0 || now - tick > 2000) return false;  // программа на ПК не пишет кадры

        uint32_t seq1 = hdr->seq;
        if (seq1 != lastSeq_ && (seq1 & 1) == 0) {
            MemoryBarrier();
            uint32_t fw = hdr->width, fh = hdr->height, fb = hdr->bpp;
            if (fw > 0 && fh > 0 && (fb == 3 || fb == 4) && (uint64_t)fw * fh <= CAMERA2PC_MAX_PIXELS) {
                size_t size = (size_t)fw * fh * fb;
                if (buf_.size() < size) buf_.resize(size);
                memcpy(buf_.data(), view_ + CAMERA2PC_HEADER_SIZE, size);
                MemoryBarrier();
                if (hdr->seq == seq1) {
                    w_ = fw; h_ = fh; bpp_ = fb;
                    lastSeq_ = seq1;
                    version_++;
                }
            }
        }
        if (!w_) return false;
        src = buf_.data(); w = w_; h = h_; bpp = bpp_;
        return true;
    }

    // Номер последнего принятого кадра: не изменился — кадр тот же, пересчитывать нечего.
    uint64_t Version() const { return version_; }

private:
    bool Ensure() {
        if (view_) return true;
        ULONGLONG now = GetTickCount64();
        if (lastTry_ && now - lastTry_ < 1000) return false;
        lastTry_ = now;

        // Доступ для всех пользователей, метка целостности Low — чтобы обычная программа могла писать
        PSECURITY_DESCRIPTOR sd = nullptr;
        SECURITY_ATTRIBUTES sa = {sizeof(sa), nullptr, FALSE};
        if (ConvertStringSecurityDescriptorToSecurityDescriptorW(
                L"D:P(A;;GA;;;WD)(A;;GA;;;SY)(A;;GA;;;LS)(A;;GA;;;BA)S:(ML;;NW;;;LW)", SDDL_REVISION_1, &sd, nullptr)) {
            sa.lpSecurityDescriptor = sd;
        }
        map_ = CreateFileMappingW(INVALID_HANDLE_VALUE, sd ? &sa : nullptr, PAGE_READWRITE, 0, CAMERA2PC_SHM_SIZE,
                                  CAMERA2PC_SHM_NAME);
        if (sd) LocalFree(sd);
        if (!map_) map_ = OpenFileMappingW(FILE_MAP_READ | FILE_MAP_WRITE, FALSE, CAMERA2PC_SHM_NAME);
        if (!map_) return false;
        view_ = static_cast<uint8_t*>(MapViewOfFile(map_, FILE_MAP_READ | FILE_MAP_WRITE, 0, 0, CAMERA2PC_SHM_SIZE));
        if (!view_) {
            CloseHandle(map_);
            map_ = nullptr;
            return false;
        }
        return true;
    }

    HANDLE map_ = nullptr;
    uint8_t* view_ = nullptr;
    ULONGLONG lastTry_ = 0;
    uint32_t lastSeq_ = 0xffffffff;
    uint64_t version_ = 0;
    std::vector<uint8_t> buf_;
    uint32_t w_ = 0, h_ = 0, bpp_ = 0;
};

// ---------------------------------------------------------------------------
// Формирование кадра нужного размера (BGRA), с сохранением пропорций
// ---------------------------------------------------------------------------
static void ComposeFrame(const uint8_t* src, uint32_t sw, uint32_t sh, uint32_t sbpp, uint32_t ow, uint32_t oh,
                         std::vector<uint8_t>& out) {
    out.resize((size_t)ow * oh * 4);
    uint32_t* dst = reinterpret_cast<uint32_t*>(out.data());
    const uint32_t bg = 0xff000000;
    if (!src) {
        // Заставка «нет сигнала»: тёмный фон и значок камеры
        const uint32_t back = 0xff202428, icon = 0xff5a6470;
        for (size_t i = 0; i < (size_t)ow * oh; i++) dst[i] = back;
        int s = (int)(oh / 8);
        int cx = (int)ow / 2, cy = (int)oh / 2;
        for (int y = cy - s; y < cy + s; y++)
            for (int x = cx - s * 2; x < cx + s; x++) dst[(size_t)y * ow + x] = icon;
        for (int y = cy - s; y < cy + s; y++) {
            int half = (y < cy ? y - (cy - s) : cy + s - y);
            for (int x = cx + s + s / 4; x < cx + s + s / 4 + half && x < (int)ow; x++) dst[(size_t)y * ow + x] = icon;
        }
        return;
    }
    // вписываем с сохранением пропорций
    uint32_t dw, dh;
    if ((uint64_t)sw * oh > (uint64_t)sh * ow) {
        dw = ow;
        dh = (uint32_t)((uint64_t)sh * ow / sw);
    } else {
        dh = oh;
        dw = (uint32_t)((uint64_t)sw * oh / sh);
    }
    if (dw == 0) dw = 1;
    if (dh == 0) dh = 1;
    uint32_t x0 = (ow - dw) / 2, y0 = (oh - dh) / 2;

    for (uint32_t y = 0; y < oh; y++) {
        uint32_t* row = dst + (size_t)y * ow;
        if (y < y0 || y >= y0 + dh) {
            for (uint32_t x = 0; x < ow; x++) row[x] = bg;
            continue;
        }
        for (uint32_t x = 0; x < x0; x++) row[x] = bg;
        for (uint32_t x = x0 + dw; x < ow; x++) row[x] = bg;
    }

    // тот же размер — просто копируем
    if (dw == sw && dh == sh) {
        for (uint32_t y = 0; y < sh; y++) {
            const uint8_t* s = src + (size_t)y * sw * sbpp;
            uint32_t* d = dst + (size_t)(y + y0) * ow + x0;
            if (sbpp == 4) {
                memcpy(d, s, (size_t)sw * 4);
                for (uint32_t x = 0; x < sw; x++) d[x] |= 0xff000000u;
            } else {
                for (uint32_t x = 0; x < sw; x++, s += 3) d[x] = 0xff000000u | ((uint32_t)s[2] << 16) | ((uint32_t)s[1] << 8) | s[0];
            }
        }
        return;
    }

    // билинейная интерполяция, веса в 1/256
    static thread_local std::vector<uint32_t> xa, xb;
    static thread_local std::vector<uint16_t> xw;
    xa.resize(dw); xb.resize(dw); xw.resize(dw);
    for (uint32_t x = 0; x < dw; x++) {
        int64_t fx = (((int64_t)x * 2 + 1) * sw * 256) / ((int64_t)dw * 2) - 128;  // центр пикселя
        if (fx < 0) fx = 0;
        uint32_t ix = (uint32_t)(fx >> 8);
        if (ix >= sw - 1) { ix = sw - 1; fx = (int64_t)ix << 8; }
        xa[x] = ix * sbpp;
        xb[x] = (ix + 1 < sw ? ix + 1 : ix) * sbpp;
        xw[x] = (uint16_t)(fx & 255);
    }
    for (uint32_t y = 0; y < dh; y++) {
        int64_t fy = (((int64_t)y * 2 + 1) * sh * 256) / ((int64_t)dh * 2) - 128;
        if (fy < 0) fy = 0;
        uint32_t iy = (uint32_t)(fy >> 8);
        if (iy >= sh - 1) { iy = sh - 1; fy = (int64_t)iy << 8; }
        uint32_t wy = (uint32_t)(fy & 255);
        const uint8_t* r0 = src + (size_t)iy * sw * sbpp;
        const uint8_t* r1 = src + (size_t)(iy + 1 < sh ? iy + 1 : iy) * sw * sbpp;
        uint32_t* d = dst + (size_t)(y + y0) * ow + x0;
        for (uint32_t x = 0; x < dw; x++) {
            uint32_t wx = xw[x];
            const uint8_t *a = r0 + xa[x], *b = r0 + xb[x], *c = r1 + xa[x], *e = r1 + xb[x];
            uint32_t px = 0xff000000u;
            for (int k = 0; k < 3; k++) {
                uint32_t top = a[k] * (256 - wx) + b[k] * wx;
                uint32_t bot = c[k] * (256 - wx) + e[k] * wx;
                uint32_t v = (top * (256 - wy) + bot * wy + 32768) >> 16;
                px |= v << (8 * k);
            }
            d[x] = px;
        }
    }
}

static void BgraToNv12(const uint8_t* bgra, uint32_t w, uint32_t h, uint8_t* yPlane, LONG yPitch, uint8_t* uvPlane,
                       LONG uvPitch) {
    for (uint32_t y = 0; y < h; y++) {
        const uint8_t* s = bgra + (size_t)y * w * 4;
        uint8_t* d = yPlane + (ptrdiff_t)y * yPitch;
        for (uint32_t x = 0; x < w; x++, s += 4) {
            int b = s[0], g = s[1], r = s[2];
            d[x] = (uint8_t)(((47 * r + 157 * g + 16 * b + 128) >> 8) + 16);  // BT.709
        }
    }
    for (uint32_t y = 0; y < h / 2; y++) {
        const uint8_t* s0 = bgra + (size_t)(2 * y) * w * 4;
        const uint8_t* s1 = s0 + (size_t)w * 4;
        uint8_t* d = uvPlane + (ptrdiff_t)y * uvPitch;
        for (uint32_t x = 0; x < w / 2; x++) {
            const uint8_t* a = s0 + x * 8;
            const uint8_t* c = s1 + x * 8;
            int b = (a[0] + a[4] + c[0] + c[4] + 2) >> 2;
            int g = (a[1] + a[5] + c[1] + c[5] + 2) >> 2;
            int r = (a[2] + a[6] + c[2] + c[6] + 2) >> 2;
            d[2 * x] = (uint8_t)(((-26 * r - 87 * g + 113 * b + 128) >> 8) + 128);
            d[2 * x + 1] = (uint8_t)(((112 * r - 102 * g - 10 * b + 128) >> 8) + 128);
        }
    }
}

static HRESULT CreateVideoType(const GUID& subtype, UINT32 w, UINT32 h, IMFMediaType** out) {
    IMFMediaType* t = nullptr;
    HRESULT hr = MFCreateMediaType(&t);
    if (FAILED(hr)) return hr;
    bool nv12 = IsEqualGUID(subtype, MFVideoFormat_NV12);
    t->SetGUID(MF_MT_MAJOR_TYPE, MFMediaType_Video);
    t->SetGUID(MF_MT_SUBTYPE, subtype);
    MFSetAttributeSize(t, MF_MT_FRAME_SIZE, w, h);
    MFSetAttributeRatio(t, MF_MT_FRAME_RATE, 30, 1);
    MFSetAttributeRatio(t, MF_MT_PIXEL_ASPECT_RATIO, 1, 1);
    t->SetUINT32(MF_MT_INTERLACE_MODE, MFVideoInterlace_Progressive);
    t->SetUINT32(MF_MT_ALL_SAMPLES_INDEPENDENT, TRUE);
    t->SetUINT32(MF_MT_FIXED_SIZE_SAMPLES, TRUE);
    t->SetUINT32(MF_MT_DEFAULT_STRIDE, nv12 ? w : w * 4);
    UINT32 size = nv12 ? w * h * 3 / 2 : w * h * 4;
    t->SetUINT32(MF_MT_SAMPLE_SIZE, size);
    t->SetUINT32(MF_MT_AVG_BITRATE, size * 8 * 30);
    if (nv12) {
        t->SetUINT32(MF_MT_YUV_MATRIX, MFVideoTransferMatrix_BT709);
        t->SetUINT32(MF_MT_VIDEO_PRIMARIES, MFVideoPrimaries_BT709);
        t->SetUINT32(MF_MT_VIDEO_NOMINAL_RANGE, MFNominalRange_16_235);
    }
    *out = t;
    return S_OK;
}

// ---------------------------------------------------------------------------
// Поток (единственный видеопоток камеры)
// ---------------------------------------------------------------------------
class Stream final : public IMFMediaStream2X, public IKsControlX {
public:
    Stream() {
        InitializeCriticalSection(&cs_);
        InterlockedIncrement(&g_objects);
    }

    HRESULT Initialize(IMFMediaSource* source) {
        source_ = source;
        source_->AddRef();
        HRESULT hr = MFCreateAttributes(&attrs_, 8);
        if (FAILED(hr)) return hr;
        attrs_->SetGUID(MF_DEVICESTREAM_STREAM_CATEGORY_X, PINNAME_VIDEO_CAPTURE_X);
        attrs_->SetUINT32(MF_DEVICESTREAM_STREAM_ID_X, 0);
        attrs_->SetUINT32(MF_DEVICESTREAM_FRAMESERVER_SHARED_X, 1);
        attrs_->SetUINT32(MF_DEVICESTREAM_ATTRIBUTE_FRAMESOURCE_TYPES_X, MFFrameSourceTypes_Color_X);

        hr = MFCreateEventQueue(&queue_);
        if (FAILED(hr)) return hr;

        struct { UINT32 w, h; } sizes[] = {{1920, 1080}, {1280, 720}, {640, 480}, {640, 360}};
        std::vector<IMFMediaType*> types;
        for (auto& s : sizes) {
            IMFMediaType* t;
            if (SUCCEEDED(CreateVideoType(MFVideoFormat_NV12, s.w, s.h, &t))) types.push_back(t);
        }
        for (auto& s : sizes) {
            IMFMediaType* t;
            if (SUCCEEDED(CreateVideoType(MFVideoFormat_RGB32, s.w, s.h, &t))) types.push_back(t);
        }
        hr = MFCreateStreamDescriptor(0, (DWORD)types.size(), types.data(), &desc_);
        if (SUCCEEDED(hr)) {
            attrs_->CopyAllItems(desc_);
            IMFMediaTypeHandler* handler = nullptr;
            if (SUCCEEDED(desc_->GetMediaTypeHandler(&handler))) {
                handler->SetCurrentMediaType(types[0]);
                handler->Release();
            }
        }
        for (auto* t : types) t->Release();
        return hr;
    }

    HRESULT Start(IMFMediaType* type) {
        Lock l(&cs_);
        if (!queue_) return MF_E_SHUTDOWN;
        IMFMediaType* current = nullptr;
        if (type) {
            current = type;
            current->AddRef();
        } else if (desc_) {
            IMFMediaTypeHandler* handler = nullptr;
            if (SUCCEEDED(desc_->GetMediaTypeHandler(&handler))) {
                handler->GetCurrentMediaType(&current);
                handler->Release();
            }
        }
        if (current) {
            current->GetGUID(MF_MT_SUBTYPE, &format_);
            MFGetAttributeSize(current, MF_MT_FRAME_SIZE, &width_, &height_);
            if (allocator_) {
                allocator_->UninitializeSampleAllocator();
                allocator_->InitializeSampleAllocator(10, current);
            }
            current->Release();
        }
        state_ = MF_STREAM_STATE_RUNNING;
        return queue_->QueueEventParamVar(MEStreamStarted, GUID_NULL, S_OK, nullptr);
    }

    HRESULT Stop() {
        Lock l(&cs_);
        if (!queue_) return MF_E_SHUTDOWN;
        if (allocator_) allocator_->UninitializeSampleAllocator();
        state_ = MF_STREAM_STATE_STOPPED;
        return queue_->QueueEventParamVar(MEStreamStopped, GUID_NULL, S_OK, nullptr);
    }

    HRESULT SetAllocator(IUnknown* unk) {
        Lock l(&cs_);
        SafeRelease(allocator_);
        if (!unk) return S_OK;
        return unk->QueryInterface(__uuidof(IMFVideoSampleAllocatorEx), (void**)&allocator_);
    }

    void Shutdown() {
        Lock l(&cs_);
        if (queue_) queue_->Shutdown();
        SafeRelease(queue_);
        SafeRelease(desc_);
        SafeRelease(attrs_);
        SafeRelease(allocator_);
        SafeRelease(source_);
    }

    MF_STREAM_STATE State() { return state_; }
    IMFStreamDescriptor* Descriptor() { return desc_; }
    IMFAttributes* Attributes() { return attrs_; }

    // IUnknown
    STDMETHODIMP QueryInterface(REFIID riid, void** ppv) override {
        if (!ppv) return E_POINTER;
        if (IsEqualIID(riid, IID_IUnknown) || IsEqualIID(riid, __uuidof(IMFMediaEventGenerator)) ||
            IsEqualIID(riid, __uuidof(IMFMediaStream)) || IsEqualIID(riid, IID_IMFMediaStream2_X)) {
            *ppv = static_cast<IMFMediaStream2X*>(this);
        } else if (IsEqualIID(riid, IID_IKsControl_X)) {
            *ppv = static_cast<IKsControlX*>(this);
        } else {
            *ppv = nullptr;
            return E_NOINTERFACE;
        }
        AddRef();
        return S_OK;
    }
    STDMETHODIMP_(ULONG) AddRef() override { return InterlockedIncrement(&ref_); }
    STDMETHODIMP_(ULONG) Release() override {
        ULONG r = InterlockedDecrement(&ref_);
        if (r == 0) delete this;
        return r;
    }

    // IMFMediaEventGenerator
    STDMETHODIMP GetEvent(DWORD flags, IMFMediaEvent** ev) override {
        IMFMediaEventQueue* q;
        {
            Lock l(&cs_);
            if (!queue_) return MF_E_SHUTDOWN;
            q = queue_;
            q->AddRef();
        }
        HRESULT hr = q->GetEvent(flags, ev);  // может блокировать — без блокировки объекта
        q->Release();
        return hr;
    }
    STDMETHODIMP BeginGetEvent(IMFAsyncCallback* cb, IUnknown* state) override {
        Lock l(&cs_);
        return queue_ ? queue_->BeginGetEvent(cb, state) : MF_E_SHUTDOWN;
    }
    STDMETHODIMP EndGetEvent(IMFAsyncResult* res, IMFMediaEvent** ev) override {
        Lock l(&cs_);
        return queue_ ? queue_->EndGetEvent(res, ev) : MF_E_SHUTDOWN;
    }
    STDMETHODIMP QueueEvent(MediaEventType met, REFGUID ext, HRESULT st, const PROPVARIANT* v) override {
        Lock l(&cs_);
        return queue_ ? queue_->QueueEventParamVar(met, ext, st, v) : MF_E_SHUTDOWN;
    }

    // IMFMediaStream
    STDMETHODIMP GetMediaSource(IMFMediaSource** src) override {
        if (!src) return E_POINTER;
        Lock l(&cs_);
        if (!source_) return MF_E_SHUTDOWN;
        *src = source_;
        source_->AddRef();
        return S_OK;
    }
    STDMETHODIMP GetStreamDescriptor(IMFStreamDescriptor** sd) override {
        if (!sd) return E_POINTER;
        Lock l(&cs_);
        if (!desc_) return MF_E_SHUTDOWN;
        *sd = desc_;
        desc_->AddRef();
        return S_OK;
    }
    STDMETHODIMP RequestSample(IUnknown* token) override {
        // держим ровно 30 к/с: расписание без накопления ошибки + точный таймер 1 мс
        static LONG timerSet = 0;
        if (InterlockedCompareExchange(&timerSet, 1, 0) == 0) timeBeginPeriod(1);
        LONGLONG now = MFGetSystemTime();
        if (nextSampleTime_ == 0 || now - nextSampleTime_ > 3 * 333333) {
            nextSampleTime_ = now;
        } else if (nextSampleTime_ > now) {
            Sleep((DWORD)((nextSampleTime_ - now) / 10000));
        }
        nextSampleTime_ += 333333;

        Lock l(&cs_);
        if (!queue_) return MF_E_SHUTDOWN;
        if (!width_ || !height_) return MF_E_INVALIDREQUEST;

        now = MFGetSystemTime();

        IMFSample* sample = nullptr;
        HRESULT hr = AllocateSample(&sample);
        if (FAILED(hr)) return hr;

        hr = Render(sample);
        if (SUCCEEDED(hr)) {
            sample->SetSampleTime(now);
            sample->SetSampleDuration(333333);
            if (token) sample->SetUnknown(MFSampleExtension_Token, token);
            hr = queue_->QueueEventParamUnk(MEMediaSample, GUID_NULL, S_OK, sample);
        }
        sample->Release();
        return hr;
    }

    // IMFMediaStream2
    STDMETHODIMP SetStreamState(MF_STREAM_STATE value) override {
        if (value == state_) return S_OK;
        switch (value) {
            case MF_STREAM_STATE_PAUSED:
                if (state_ != MF_STREAM_STATE_RUNNING) return MF_E_INVALID_STATE_TRANSITION;
                state_ = value;
                return S_OK;
            case MF_STREAM_STATE_RUNNING:
                return Start(nullptr);
            case MF_STREAM_STATE_STOPPED:
                return Stop();
            default:
                return MF_E_INVALID_STATE_TRANSITION;
        }
    }
    STDMETHODIMP GetStreamState(MF_STREAM_STATE* value) override {
        if (!value) return E_POINTER;
        *value = state_;
        return S_OK;
    }

    // IKsControl
    STDMETHODIMP_(LONG) KsProperty(void*, ULONG, void*, ULONG, ULONG*) override { return KS_NOT_FOUND; }
    STDMETHODIMP_(LONG) KsMethod(void*, ULONG, void*, ULONG, ULONG*) override { return KS_NOT_FOUND; }
    STDMETHODIMP_(LONG) KsEvent(void*, ULONG, void*, ULONG, ULONG*) override { return KS_NOT_FOUND; }

private:
    ~Stream() {
        Shutdown();
        DeleteCriticalSection(&cs_);
        InterlockedDecrement(&g_objects);
    }

    bool IsNv12() const { return IsEqualGUID(format_, MFVideoFormat_NV12); }

    HRESULT AllocateSample(IMFSample** out) {
        if (allocator_) {
            HRESULT hr = allocator_->AllocateSample(out);
            if (SUCCEEDED(hr)) return hr;
        }
        DWORD size = IsNv12() ? width_ * height_ * 3 / 2 : width_ * height_ * 4;
        IMFSample* s = nullptr;
        IMFMediaBuffer* b = nullptr;
        HRESULT hr = MFCreateSample(&s);
        if (SUCCEEDED(hr)) hr = MFCreateMemoryBuffer(size, &b);
        if (SUCCEEDED(hr)) hr = b->SetCurrentLength(size);
        if (SUCCEEDED(hr)) hr = s->AddBuffer(b);
        SafeRelease(b);
        if (FAILED(hr)) {
            SafeRelease(s);
            return hr;
        }
        *out = s;
        return S_OK;
    }

    HRESULT Render(IMFSample* sample) {
        const uint8_t* src = nullptr;
        uint32_t sw = 0, sh = 0, sbpp = 0;
        if (!reader_.Read(src, sw, sh, sbpp)) src = nullptr;
        // Кадр не изменился (телефон даёт 30 к/с, но бывает и меньше, а заставка вообще статична) —
        // берём уже готовый результат, не масштабируем и не конвертируем заново.
        const uint64_t key = src ? reader_.Version() : 0;
        const bool nv12 = IsNv12();
        if (cache_.empty() || key != cacheKey_ || nv12 != cacheNv12_ || width_ != cacheW_ || height_ != cacheH_) {
            ComposeFrame(src, sw, sh, sbpp, width_, height_, frame_);
            if (nv12) {
                cache_.resize((size_t)width_ * height_ * 3 / 2);
                BgraToNv12(frame_.data(), width_, height_, cache_.data(), (LONG)width_,
                           cache_.data() + (size_t)width_ * height_, (LONG)width_);
            } else {
                cache_ = frame_;
            }
            cacheKey_ = key;
            cacheNv12_ = nv12;
            cacheW_ = width_;
            cacheH_ = height_;
        }

        IMFMediaBuffer* buffer = nullptr;
        HRESULT hr = sample->GetBufferByIndex(0, &buffer);
        if (FAILED(hr)) return hr;

        IMF2DBuffer* buf2d = nullptr;
        BYTE* data = nullptr;
        LONG pitch = 0;
        DWORD maxLen = 0;
        bool locked2d = false;
        if (SUCCEEDED(buffer->QueryInterface(__uuidof(IMF2DBuffer), (void**)&buf2d)) &&
            SUCCEEDED(buf2d->Lock2D(&data, &pitch))) {
            locked2d = true;
        } else {
            SafeRelease(buf2d);
            hr = buffer->Lock(&data, &maxLen, nullptr);
            if (FAILED(hr)) {
                buffer->Release();
                return hr;
            }
            pitch = IsNv12() ? (LONG)width_ : (LONG)width_ * 4;
        }

        if (nv12) {
            for (uint32_t y = 0; y < height_; y++)
                memcpy(data + (ptrdiff_t)y * pitch, cache_.data() + (size_t)y * width_, width_);
            uint8_t* uv = data + (ptrdiff_t)pitch * height_;
            const uint8_t* srcUv = cache_.data() + (size_t)width_ * height_;
            for (uint32_t y = 0; y < height_ / 2; y++)
                memcpy(uv + (ptrdiff_t)y * pitch, srcUv + (size_t)y * width_, width_);
        } else {
            for (uint32_t y = 0; y < height_; y++)
                memcpy(data + (ptrdiff_t)y * pitch, cache_.data() + (size_t)y * width_ * 4, (size_t)width_ * 4);
        }

        if (locked2d) {
            buf2d->Unlock2D();
            buf2d->Release();
        } else {
            buffer->Unlock();
        }
        buffer->SetCurrentLength(IsNv12() ? width_ * height_ * 3 / 2 : width_ * height_ * 4);
        buffer->Release();
        return S_OK;
    }

    volatile LONG ref_ = 1;
    CRITICAL_SECTION cs_;
    IMFMediaSource* source_ = nullptr;
    IMFAttributes* attrs_ = nullptr;
    IMFMediaEventQueue* queue_ = nullptr;
    IMFStreamDescriptor* desc_ = nullptr;
    IMFVideoSampleAllocatorEx* allocator_ = nullptr;
    volatile MF_STREAM_STATE state_ = MF_STREAM_STATE_STOPPED;
    GUID format_ = MFVideoFormat_NV12;
    UINT32 width_ = 0, height_ = 0;
    LONGLONG nextSampleTime_ = 0;
    FrameReader reader_;
    std::vector<uint8_t> frame_;
    std::vector<uint8_t> cache_;  // готовый кадр в формате потока (NV12 или BGRA)
    uint64_t cacheKey_ = ~0ull;
    bool cacheNv12_ = false;
    UINT32 cacheW_ = 0, cacheH_ = 0;
};

// ---------------------------------------------------------------------------
// Источник
// ---------------------------------------------------------------------------
class Source final : public IMFMediaSource2X,
                     public IMFGetService,
                     public IKsControlX,
                     public IMFSampleAllocatorControlX,
                     public IMFAttributes {
public:
    Source() {
        InitializeCriticalSection(&cs_);
        InterlockedIncrement(&g_objects);
    }

    HRESULT Initialize(IMFAttributes* init) {
        HRESULT hr = MFCreateAttributes(&attrs_, 10);
        if (FAILED(hr)) return hr;
        if (init) init->CopyAllItems(attrs_);

        stream_ = new Stream();
        hr = stream_->Initialize(static_cast<IMFMediaSource*>(static_cast<IMFMediaSource2X*>(this)));
        if (FAILED(hr)) return hr;

        IMFStreamDescriptor* sd = stream_->Descriptor();
        hr = MFCreatePresentationDescriptor(1, &sd, &pd_);
        if (FAILED(hr)) return hr;
        return MFCreateEventQueue(&queue_);
    }

    // IUnknown
    STDMETHODIMP QueryInterface(REFIID riid, void** ppv) override {
        if (!ppv) return E_POINTER;
        if (IsEqualIID(riid, IID_IUnknown) || IsEqualIID(riid, __uuidof(IMFMediaEventGenerator)) ||
            IsEqualIID(riid, __uuidof(IMFMediaSource)) || IsEqualIID(riid, IID_IMFMediaSourceEx_X) ||
            IsEqualIID(riid, IID_IMFMediaSource2_X)) {
            *ppv = static_cast<IMFMediaSource2X*>(this);
        } else if (IsEqualIID(riid, __uuidof(IMFGetService))) {
            *ppv = static_cast<IMFGetService*>(this);
        } else if (IsEqualIID(riid, IID_IKsControl_X)) {
            *ppv = static_cast<IKsControlX*>(this);
        } else if (IsEqualIID(riid, IID_IMFSampleAllocatorControl_X)) {
            *ppv = static_cast<IMFSampleAllocatorControlX*>(this);
        } else if (IsEqualIID(riid, __uuidof(IMFAttributes))) {
            *ppv = static_cast<IMFAttributes*>(this);
        } else {
            *ppv = nullptr;
            return E_NOINTERFACE;
        }
        AddRef();
        return S_OK;
    }
    STDMETHODIMP_(ULONG) AddRef() override { return InterlockedIncrement(&ref_); }
    STDMETHODIMP_(ULONG) Release() override {
        ULONG r = InterlockedDecrement(&ref_);
        if (r == 0) delete this;
        return r;
    }

    // IMFMediaEventGenerator
    STDMETHODIMP GetEvent(DWORD flags, IMFMediaEvent** ev) override {
        IMFMediaEventQueue* q;
        {
            Lock l(&cs_);
            if (!queue_) return MF_E_SHUTDOWN;
            q = queue_;
            q->AddRef();
        }
        HRESULT hr = q->GetEvent(flags, ev);
        q->Release();
        return hr;
    }
    STDMETHODIMP BeginGetEvent(IMFAsyncCallback* cb, IUnknown* state) override {
        Lock l(&cs_);
        return queue_ ? queue_->BeginGetEvent(cb, state) : MF_E_SHUTDOWN;
    }
    STDMETHODIMP EndGetEvent(IMFAsyncResult* res, IMFMediaEvent** ev) override {
        Lock l(&cs_);
        return queue_ ? queue_->EndGetEvent(res, ev) : MF_E_SHUTDOWN;
    }
    STDMETHODIMP QueueEvent(MediaEventType met, REFGUID ext, HRESULT st, const PROPVARIANT* v) override {
        Lock l(&cs_);
        return queue_ ? queue_->QueueEventParamVar(met, ext, st, v) : MF_E_SHUTDOWN;
    }

    // IMFMediaSource
    STDMETHODIMP GetCharacteristics(DWORD* c) override {
        if (!c) return E_POINTER;
        *c = MFMEDIASOURCE_IS_LIVE;
        return S_OK;
    }
    STDMETHODIMP CreatePresentationDescriptor(IMFPresentationDescriptor** out) override {
        if (!out) return E_POINTER;
        Lock l(&cs_);
        if (!pd_) return MF_E_SHUTDOWN;
        return pd_->Clone(out);
    }
    STDMETHODIMP Start(IMFPresentationDescriptor* pd, const GUID* timeFormat, const PROPVARIANT* startPos) override {
        if (!pd) return E_POINTER;
        if (timeFormat && !IsEqualGUID(*timeFormat, GUID_NULL)) return MF_E_UNSUPPORTED_TIME_FORMAT;
        (void)startPos;
        Lock l(&cs_);
        if (!queue_ || !pd_) return MF_E_SHUTDOWN;

        DWORD count = 0;
        HRESULT hr = pd->GetStreamDescriptorCount(&count);
        if (FAILED(hr)) return hr;
        for (DWORD i = 0; i < count; i++) {
            BOOL selected = FALSE;
            IMFStreamDescriptor* sd = nullptr;
            hr = pd->GetStreamDescriptorByIndex(i, &selected, &sd);
            if (FAILED(hr)) return hr;
            DWORD id = 0;
            sd->GetStreamIdentifier(&id);
            if (id != 0) {
                sd->Release();
                continue;
            }
            bool running = stream_->State() != MF_STREAM_STATE_STOPPED;
            if (selected && !running) {
                pd_->SelectStream(0);
                queue_->QueueEventParamUnk(MENewStream, GUID_NULL, S_OK,
                                           static_cast<IMFMediaStream*>(static_cast<IMFMediaStream2X*>(stream_)));
                IMFMediaTypeHandler* handler = nullptr;
                IMFMediaType* type = nullptr;
                if (SUCCEEDED(sd->GetMediaTypeHandler(&handler))) {
                    handler->GetCurrentMediaType(&type);
                    handler->Release();
                }
                hr = stream_->Start(type);
                SafeRelease(type);
            } else if (selected && running) {
                queue_->QueueEventParamUnk(MEUpdatedStream, GUID_NULL, S_OK,
                                           static_cast<IMFMediaStream*>(static_cast<IMFMediaStream2X*>(stream_)));
            } else if (!selected && running) {
                pd_->DeselectStream(0);
                hr = stream_->Stop();
            }
            sd->Release();
            if (FAILED(hr)) return hr;
        }

        PROPVARIANT time;
        PropVariantInit(&time);
        time.vt = VT_I8;
        time.hVal.QuadPart = MFGetSystemTime();
        return queue_->QueueEventParamVar(MESourceStarted, GUID_NULL, S_OK, &time);
    }
    STDMETHODIMP Stop() override {
        Lock l(&cs_);
        if (!queue_ || !pd_) return MF_E_SHUTDOWN;
        if (stream_->State() != MF_STREAM_STATE_STOPPED) stream_->Stop();
        pd_->DeselectStream(0);
        PROPVARIANT time;
        PropVariantInit(&time);
        time.vt = VT_I8;
        time.hVal.QuadPart = MFGetSystemTime();
        return queue_->QueueEventParamVar(MESourceStopped, GUID_NULL, S_OK, &time);
    }
    STDMETHODIMP Pause() override { return MF_E_INVALID_STATE_TRANSITION; }
    STDMETHODIMP Shutdown() override {
        Lock l(&cs_);
        if (!queue_) return MF_E_SHUTDOWN;
        queue_->Shutdown();
        SafeRelease(queue_);
        if (stream_) {
            stream_->Shutdown();
            stream_->Release();
            stream_ = nullptr;
        }
        SafeRelease(pd_);
        return S_OK;
    }

    // IMFMediaSourceEx
    STDMETHODIMP GetSourceAttributes(IMFAttributes** out) override {
        if (!out) return E_POINTER;
        *out = static_cast<IMFAttributes*>(this);
        AddRef();
        return S_OK;
    }
    STDMETHODIMP GetStreamAttributes(DWORD id, IMFAttributes** out) override {
        if (!out) return E_POINTER;
        Lock l(&cs_);
        if (!stream_ || id != 0) return MF_E_INVALIDSTREAMNUMBER;
        *out = stream_->Attributes();
        if (!*out) return MF_E_SHUTDOWN;
        (*out)->AddRef();
        return S_OK;
    }
    STDMETHODIMP SetD3DManager(IUnknown*) override { return S_OK; }  // работаем на CPU

    // IMFMediaSource2
    STDMETHODIMP SetMediaType(DWORD, IMFMediaType* type) override { return type ? S_OK : E_POINTER; }

    // IMFGetService
    STDMETHODIMP GetService(REFGUID, REFIID, LPVOID*) override { return MF_E_UNSUPPORTED_SERVICE; }

    // IMFSampleAllocatorControl
    STDMETHODIMP SetDefaultAllocator(DWORD id, IUnknown* allocator) override {
        Lock l(&cs_);
        if (!stream_ || id != 0) return MF_E_INVALIDSTREAMNUMBER;
        return stream_->SetAllocator(allocator);
    }
    STDMETHODIMP GetAllocatorUsage(DWORD id, DWORD* inId, MFSampleAllocatorUsageX* usage) override {
        if (!inId || !usage) return E_POINTER;
        *inId = id;
        *usage = MFSampleAllocatorUsage_UsesProvidedAllocator_X;
        return S_OK;
    }

    // IKsControl
    STDMETHODIMP_(LONG) KsProperty(void*, ULONG, void*, ULONG, ULONG*) override { return KS_NOT_FOUND; }
    STDMETHODIMP_(LONG) KsMethod(void*, ULONG, void*, ULONG, ULONG*) override { return KS_NOT_FOUND; }
    STDMETHODIMP_(LONG) KsEvent(void*, ULONG, void*, ULONG, ULONG*) override { return KS_NOT_FOUND; }

    // IMFAttributes
    DELEGATE_IMFATTRIBUTES(attrs_)

private:
    ~Source() {
        if (stream_) {
            stream_->Shutdown();
            stream_->Release();
        }
        SafeRelease(queue_);
        SafeRelease(pd_);
        SafeRelease(attrs_);
        DeleteCriticalSection(&cs_);
        InterlockedDecrement(&g_objects);
    }

    volatile LONG ref_ = 1;
    CRITICAL_SECTION cs_;
    IMFAttributes* attrs_ = nullptr;
    IMFMediaEventQueue* queue_ = nullptr;
    IMFPresentationDescriptor* pd_ = nullptr;
    Stream* stream_ = nullptr;
};

// ---------------------------------------------------------------------------
// Активатор (его создаёт Frame Server через CoCreateInstance)
// ---------------------------------------------------------------------------
class Activator final : public IMFActivate {
public:
    Activator() {
        InitializeCriticalSection(&cs_);
        InterlockedIncrement(&g_objects);
    }

    HRESULT Initialize() {
        HRESULT hr = MFCreateAttributes(&attrs_, 4);
        if (FAILED(hr)) return hr;
        attrs_->SetUINT32(MF_VIRTUALCAMERA_PROVIDE_ASSOCIATED_CAMERA_SOURCES_X, 1);
        attrs_->SetGUID(MFT_TRANSFORM_CLSID_Attribute_X, CLSID_Camera2PCSource);
        return S_OK;
    }

    STDMETHODIMP QueryInterface(REFIID riid, void** ppv) override {
        if (!ppv) return E_POINTER;
        if (IsEqualIID(riid, IID_IUnknown) || IsEqualIID(riid, __uuidof(IMFAttributes)) ||
            IsEqualIID(riid, __uuidof(IMFActivate))) {
            *ppv = static_cast<IMFActivate*>(this);
            AddRef();
            return S_OK;
        }
        *ppv = nullptr;
        return E_NOINTERFACE;
    }
    STDMETHODIMP_(ULONG) AddRef() override { return InterlockedIncrement(&ref_); }
    STDMETHODIMP_(ULONG) Release() override {
        ULONG r = InterlockedDecrement(&ref_);
        if (r == 0) delete this;
        return r;
    }

    STDMETHODIMP ActivateObject(REFIID riid, void** ppv) override {
        if (!ppv) return E_POINTER;
        *ppv = nullptr;
        Lock l(&cs_);
        if (!source_) {
            Source* s = new Source();
            HRESULT hr = s->Initialize(attrs_);
            if (FAILED(hr)) {
                s->Release();
                return hr;
            }
            source_ = s;
        }
        return source_->QueryInterface(riid, ppv);
    }
    STDMETHODIMP ShutdownObject() override {
        Lock l(&cs_);
        if (source_) {
            source_->Shutdown();
            source_->Release();
            source_ = nullptr;
        }
        return S_OK;
    }
    STDMETHODIMP DetachObject() override {
        Lock l(&cs_);
        if (source_) {
            source_->Release();
            source_ = nullptr;
        }
        return S_OK;
    }

    DELEGATE_IMFATTRIBUTES(attrs_)

private:
    ~Activator() {
        if (source_) source_->Release();
        SafeRelease(attrs_);
        DeleteCriticalSection(&cs_);
        InterlockedDecrement(&g_objects);
    }

    volatile LONG ref_ = 1;
    CRITICAL_SECTION cs_;
    IMFAttributes* attrs_ = nullptr;
    Source* source_ = nullptr;
};

// ---------------------------------------------------------------------------
// Фабрика класса и экспорт DLL
// ---------------------------------------------------------------------------
class ClassFactory final : public IClassFactory {
public:
    STDMETHODIMP QueryInterface(REFIID riid, void** ppv) override {
        if (!ppv) return E_POINTER;
        if (IsEqualIID(riid, IID_IUnknown) || IsEqualIID(riid, IID_IClassFactory)) {
            *ppv = static_cast<IClassFactory*>(this);
            AddRef();
            return S_OK;
        }
        *ppv = nullptr;
        return E_NOINTERFACE;
    }
    STDMETHODIMP_(ULONG) AddRef() override { return InterlockedIncrement(&ref_); }
    STDMETHODIMP_(ULONG) Release() override {
        ULONG r = InterlockedDecrement(&ref_);
        if (r == 0) delete this;
        return r;
    }
    STDMETHODIMP CreateInstance(IUnknown* outer, REFIID riid, void** ppv) override {
        if (!ppv) return E_POINTER;
        *ppv = nullptr;
        if (outer) return CLASS_E_NOAGGREGATION;
        Activator* a = new Activator();
        HRESULT hr = a->Initialize();
        if (SUCCEEDED(hr)) {
            hr = a->QueryInterface(riid, ppv);
            if (FAILED(hr)) hr = a->ActivateObject(riid, ppv);  // на случай прямого запроса источника
        }
        a->Release();
        return hr;
    }
    STDMETHODIMP LockServer(BOOL lock) override {
        if (lock) InterlockedIncrement(&g_locks);
        else InterlockedDecrement(&g_locks);
        return S_OK;
    }

private:
    volatile LONG ref_ = 1;
};

extern "C" BOOL WINAPI DllMain(HINSTANCE inst, DWORD reason, LPVOID) {
    if (reason == DLL_PROCESS_ATTACH) {
        g_module = inst;
        DisableThreadLibraryCalls(inst);
    }
    return TRUE;
}

extern "C" HRESULT __stdcall DllGetClassObject(REFCLSID clsid, REFIID riid, void** ppv) {
    if (!ppv) return E_POINTER;
    *ppv = nullptr;
    if (!IsEqualCLSID(clsid, CLSID_Camera2PCSource)) return CLASS_E_CLASSNOTAVAILABLE;
    ClassFactory* f = new ClassFactory();
    HRESULT hr = f->QueryInterface(riid, ppv);
    f->Release();
    return hr;
}

extern "C" HRESULT __stdcall DllCanUnloadNow() {
    return (g_objects == 0 && g_locks == 0) ? S_OK : S_FALSE;
}

static LONG SetRegString(HKEY key, const wchar_t* name, const wchar_t* value) {
    return RegSetValueExW(key, name, 0, REG_SZ, (const BYTE*)value, (DWORD)((wcslen(value) + 1) * sizeof(wchar_t)));
}

// Регистрация обязательно в HKLM — иначе Frame Server не найдёт класс
extern "C" HRESULT __stdcall DllRegisterServer() {
    wchar_t path[MAX_PATH];
    if (!GetModuleFileNameW(g_module, path, MAX_PATH)) return HRESULT_FROM_WIN32(GetLastError());
    HKEY clsKey = nullptr, srvKey = nullptr;
    LONG r = RegCreateKeyExW(HKEY_LOCAL_MACHINE, L"Software\\Classes\\CLSID\\" CAMERA2PC_CLSID_STR, 0, nullptr, 0,
                             KEY_WRITE, nullptr, &clsKey, nullptr);
    if (r != ERROR_SUCCESS) return HRESULT_FROM_WIN32(r);
    SetRegString(clsKey, nullptr, L"Oko Virtual Camera Source");
    r = RegCreateKeyExW(clsKey, L"InprocServer32", 0, nullptr, 0, KEY_WRITE, nullptr, &srvKey, nullptr);
    if (r == ERROR_SUCCESS) {
        SetRegString(srvKey, nullptr, path);
        SetRegString(srvKey, L"ThreadingModel", L"Both");
        RegCloseKey(srvKey);
    }
    RegCloseKey(clsKey);
    return HRESULT_FROM_WIN32(r);
}

extern "C" HRESULT __stdcall DllUnregisterServer() {
    LONG r = RegDeleteTreeW(HKEY_LOCAL_MACHINE, L"Software\\Classes\\CLSID\\" CAMERA2PC_CLSID_STR);
    return (r == ERROR_SUCCESS || r == ERROR_FILE_NOT_FOUND) ? S_OK : HRESULT_FROM_WIN32(r);
}
