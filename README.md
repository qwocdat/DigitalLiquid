# DigitalLiquid

Ứng dụng Android Kotlin cho lớp phủ Dynamic Island tùy biến. Đây là bản phát triển, cần kiểm tra trên thiết bị thật trước khi phát hành rộng rãi.

## Thay đổi trong bản này

- Bỏ màn hình xác thực Get Key và bỏ khóa theo hãng/hệ điều hành. Lần mở đầu cho chọn **Hybrid Island** hoặc **Dynamic Island đầy đủ**; có thể đổi lại trong Cài đặt.
- Làm mới màn hình chọn chế độ, Live Preview và phần tùy chỉnh hiệu ứng.
- Live Preview đọc trạng thái media session, thông báo đang hoạt động và các trạng thái ghi màn hình/camera mà ứng dụng được hệ thống cung cấp; nếu chưa cấp Notification Access, một số trạng thái không thể đọc.
- Thay animation thu phóng dựa trên `scaleX/scaleY` ở cửa sổ chính bằng nội suy kích thước/corner radius, đồng bộ vùng đệm trong suốt của cửa sổ và pill để hạn chế hiện tượng khối vuông.
- Thêm lựa chọn kiểu animation, thời lượng morph, opacity, độ bóng, motion blur, Liquid Glass và các mức FPS yêu cầu. Máy chỉ áp dụng mức refresh rate mà màn hình thực sự hỗ trợ; chọn 165 FPS không thể biến màn hình 60 Hz thành 165 Hz.
- Bổ sung `libantidigitalsmod.so` để kiểm tra SHA-256 của chứng chỉ ký APK trong mã native. Bản debug chạy được khi chưa cấu hình fingerprint; bản release cần cấu hình fingerprint chính xác.
- Bổ sung Cloudflare Worker làm proxy AI. Ứng dụng không yêu cầu người dùng nhập API key và không chứa OpenRouter secret.

## Build debug APK

Workflow GitHub Actions tại `.github/workflows/build-apk.yml` cài Android SDK, NDK và CMake rồi chạy `gradle assembleDebug`. Mở tab **Actions**, chọn workflow **Build APK**, chạy workflow thủ công và tải artifact `DigitalLiquid-Debug-APK`.

## Cấu hình AI một lần cho nhà phát hành

1. Cài Node.js và Wrangler, vào thư mục `server`, rồi sao chép `wrangler.toml.example` thành `wrangler.toml`.
2. Trong Firebase project `ideo-dd376`, bật Anonymous Authentication. Cần dùng đúng Firebase Web API Key của chính project này.
3. Trong thư mục `server`, đặt secret bằng lệnh:

   ```sh
   npx wrangler secret put OPENROUTER_API_KEY
   npx wrangler secret put FIREBASE_API_KEY
   ```

4. Deploy Worker:

   ```sh
   npx wrangler deploy
   ```

5. Thay `AI_PROXY_ENDPOINT` trong `app/src/main/java/com/digitalsmod/digitalliquid/OpenRouterClient.kt` bằng URL HTTPS vừa deploy, rồi build ứng dụng. Đây là việc của nhà phát hành, không phải người dùng cuối.
6. Thiết lập quy tắc rate limit ở Cloudflare (ví dụ bắt đầu ở 10 yêu cầu/IP/phút), theo dõi chi phí OpenRouter, đặt hạn mức ngân sách và điều chỉnh theo lưu lượng thực. Firebase Anonymous Auth không tự ngăn một người tạo nhiều tài khoản ẩn danh.

**Bảo mật key:** key OpenRouter từng được dán trong cuộc trò chuyện nên hãy thu hồi/rotate key đó ngay, sau đó chỉ đưa key mới vào Worker Secrets. Không đưa key vào Kotlin, `BuildConfig`, GitHub commit hay APK. Bất kỳ key nào nhúng trong APK đều có thể bị trích xuất.

## Kiểm tra chữ ký APK và giới hạn của `.so`

Để phát hành bản release, hãy ký APK bằng keystore riêng. Lấy SHA-256 fingerprint của chứng chỉ ký, rồi cung cấp biến môi trường `EXPECTED_SIGNER_SHA256` khi build. Giá trị phải là fingerprint SHA-256 64 ký tự hex của đúng chứng chỉ release. Giữ keystore và mật khẩu ở GitHub Actions Secrets; không commit lên repo.

`libantidigitalsmod.so` kiểm tra fingerprint chứng chỉ ký, không “mã hóa toàn bộ mã nguồn”. Android cần bytecode để chạy ứng dụng, vì vậy một người có kỹ năng vẫn có thể phân tích DEX/native hoặc vá kiểm tra. R8 shrinking/obfuscation và kiểm tra chữ ký chỉ làm tăng độ khó, không tạo ra bảo vệ tuyệt đối. Nếu ứng dụng bị sửa hoặc ký lại bằng chứng chỉ khác, kiểm tra trong app sẽ chặn khi fingerprint release được cấu hình đúng.

## Giới hạn cần biết

- Android không cung cấp cho ứng dụng bên thứ ba quyền thay thế hoàn toàn UI hệ thống. Chỉ báo camera/micro, một số thông báo và nội dung điều hướng có thể bị giới hạn bởi phiên bản Android, ROM và quyền đã cấp.
- Hybrid giữ tương thích với hành vi ẩn/hiện của hệ điều hành; Full cố gắng duy trì overlay khi có hoạt động khác nhưng không thể che hoặc chiếm quyền các chỉ báo riêng tư được Android bảo vệ.
- Mức FPS là yêu cầu với hệ thống, không phải cam kết. Chất lượng thực tế phụ thuộc GPU, refresh rate, nhiệt độ, chế độ tiết kiệm pin và tác vụ nền.
- Mã cần build và test trên thiết bị Android thật. Không có cách hợp lý nào bảo đảm “giống iOS 100%” hoặc “không lag trên mọi máy”; mục tiêu là chuyển động liên tục, bo góc đúng và giảm lỗi hình học rõ rệt.
