package com.nguyenhuuquang.hotelmanagement.service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestTemplate;

import com.nguyenhuuquang.hotelmanagement.dto.ChatRequest;
import com.nguyenhuuquang.hotelmanagement.dto.ChatResponse;
import com.nguyenhuuquang.hotelmanagement.entity.ChatMessage;
import com.nguyenhuuquang.hotelmanagement.entity.Promotion;
import com.nguyenhuuquang.hotelmanagement.entity.Room;
import com.nguyenhuuquang.hotelmanagement.entity.RoomType;
import com.nguyenhuuquang.hotelmanagement.repository.BookingRepository;
import com.nguyenhuuquang.hotelmanagement.repository.ChatMessageRepository;
import com.nguyenhuuquang.hotelmanagement.repository.PromotionRepository;
import com.nguyenhuuquang.hotelmanagement.repository.RoomRepository;
import com.nguyenhuuquang.hotelmanagement.repository.RoomTypeRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class ChatbotService {

    private final ChatMessageRepository chatMessageRepository;
    private final RoomRepository roomRepository;
    private final RoomTypeRepository roomTypeRepository;
    private final BookingRepository bookingRepository;
    private final PromotionRepository promotionRepository;
    private final RestTemplate restTemplate = new RestTemplate();

    @Value("${gemini.api.key}")
    private String geminiApiKey;

    @Value("${gemini.api.url}")
    private String geminiApiUrl;

    public ChatResponse sendMessage(ChatRequest request) {
        try {
            log.info("📤 Sending message to Gemini AI: {}", request.getMessage());

            // Build system instruction (sent separately as systemInstruction)
            String systemInstruction = buildSystemInstruction();

            // Build context từ database
            String realtimeData = buildRealtimeData();

            // Build full user message: context + câu hỏi
            String userMessage = realtimeData + "\n\n" +
                    "CÂU HỎI CỦA NHÂN VIÊN: " + request.getMessage();

            String aiResponse = callGeminiAPI(systemInstruction, userMessage);

            ChatMessage chatMessage = ChatMessage.builder()
                    .userMessage(request.getMessage())
                    .aiResponse(aiResponse)
                    .userId(request.getUserId())
                    .build();

            chatMessage = chatMessageRepository.save(chatMessage);

            log.info("✅ AI Response generated and saved successfully");

            return ChatResponse.builder()
                    .id(chatMessage.getId())
                    .userMessage(chatMessage.getUserMessage())
                    .aiResponse(chatMessage.getAiResponse())
                    .timestamp(chatMessage.getCreatedAt())
                    .build();

        } catch (Exception e) {
            log.error("❌ Error in chatbot service: ", e);
            throw new RuntimeException("Không thể kết nối với AI chatbot: " + e.getMessage());
        }
    }

    // ============================================================
    // SYSTEM INSTRUCTION - Vai trò và kiến thức nghiệp vụ
    // ============================================================
    private String buildSystemInstruction() {
        return """
                Bạn là trợ lý AI thông minh của hệ thống quản lý khách sạn Hotel Manager.
                Nhiệm vụ: Hỗ trợ nhân viên lễ tân và quản lý khách sạn trả lời mọi câu hỏi về nghiệp vụ.

                NGÔN NGỮ: Luôn trả lời bằng tiếng Việt, ngắn gọn và chính xác.

                === NGHIỆP VỤ HỆ THỐNG ===

                QUY TRÌNH ĐẶT PHÒNG (BOOKING FLOW):
                Luồng trạng thái: PENDING → CONFIRMED → CHECKED_IN → CHECKED_OUT → COMPLETED
                Trạng thái đặc biệt: CANCELLED (hủy), NO_SHOW (không đến)

                Chi tiết từng bước:
                1. PENDING: Booking vừa tạo, chờ lễ tân xác nhận. Phòng chuyển sang RESERVED.
                2. CONFIRMED: Lễ tân xác nhận. Chỉ xác nhận khi phòng không có khách CHECKED_IN.
                3. CHECKED_IN: Khách nhận phòng. Phòng chuyển sang OCCUPIED.
                4. CHECKED_OUT: Khách trả phòng. Phòng chuyển sang CLEANING.
                5. COMPLETED: Thanh toán xong, phòng trở về AVAILABLE.
                6. CANCELLED: Hủy booking, phòng trả về AVAILABLE.
                7. NO_SHOW: Khách không đến sau CONFIRMED, không hoàn cọc.

                TRẠNG THÁI PHÒNG:
                - AVAILABLE: Trống, sẵn sàng nhận khách
                - RESERVED: Đã đặt nhưng chưa check-in
                - OCCUPIED: Đang có khách ở
                - CLEANING: Đang dọn dẹp sau khi trả phòng
                - MAINTENANCE: Đang bảo trì
                - OUT_OF_ORDER: Hỏng, ngừng hoạt động

                TÍNH TIỀN PHÒNG:
                Tổng tiền = (Giá phòng × Số đêm) + Tiền dịch vụ - Giảm giá (khuyến mãi)
                Số đêm = Ngày check-out - Ngày check-in
                Tiền cọc (Deposit): Thu trước, trừ vào tổng khi thanh toán

                QUY TẮC THANH TOÁN:
                - Cọc: Thu khi xác nhận booking (thường 30% tổng tiền)
                - Thanh toán hoàn tất: Thu khi check-out (Tổng - Cọc đã trả)
                - Phương thức: CASH (tiền mặt), CARD (thẻ), TRANSFER (chuyển khoản)
                - Tích hợp PayOS: Quét mã QR cho cả thanh toán cọc và checkout

                MÃ KHUYẾN MÃI (PROMOTION):
                - PERCENTAGE: Giảm theo % (VD: giảm 10%, tối đa 200.000đ)
                - FIXED_AMOUNT: Giảm số tiền cố định (VD: giảm 100.000đ)
                - Điều kiện: Mã phải còn hiệu lực, giá trị booking đạt tối thiểu, chưa vượt số lần dùng
                - Mỗi booking có thể áp dụng nhiều mã khuyến mãi

                DỊCH VỤ PHÒNG:
                - Thêm vào booking bất kỳ lúc nào khi chưa COMPLETED/CANCELLED
                - Tiền dịch vụ tự động cộng vào tổng tiền booking

                ĐỔI PHÒNG:
                - Chỉ đổi khi booking đang PENDING hoặc CONFIRMED
                - Phòng mới phải AVAILABLE và không có booking CHECKED_IN trùng thời gian

                QUẢN LÝ TÀI CHÍNH:
                - INCOME (Thu): Tiền phòng, dịch vụ, đặt cọc
                - EXPENSE (Chi): Chi phí vận hành, lương, vật tư
                - Lợi nhuận = Thu - Chi

                QUY TRÌNH NHÂN VIÊN - CHECK-IN KHÁCH:
                1. Tìm booking → Xác nhận thông tin khách và ngày
                2. Nhấn CONFIRM nếu booking còn PENDING
                3. Nhấn CHECK-IN khi khách đến
                4. Thu tiền cọc nếu chưa thu

                QUY TRÌNH NHÂN VIÊN - CHECK-OUT KHÁCH:
                1. Tìm booking đang CHECKED_IN
                2. Kiểm tra các dịch vụ phát sinh
                3. Tính tổng tiền còn lại (Tổng - Cọc đã trả)
                4. Thu tiền → Nhấn CHECK-OUT → Nhấn COMPLETE
                5. Tạo lịch sử checkout

                QUY TRÌNH XỬ LÝ HỦY:
                - PENDING/CONFIRMED: Nhấn CANCEL, hoàn tiền cọc nếu có
                - Khách không đến: Nhấn NO-SHOW, không hoàn cọc
                - Không thể hủy booking đã COMPLETED

                QUY TẮC QUAN TRỌNG:
                1. Một phòng chỉ có 1 booking CHECKED_IN cùng lúc
                2. Booking đang CHECKED_IN không được hủy trực tiếp, phải checkout trước
                3. Phòng đang OCCUPIED hoặc RESERVED không được xóa
                4. Loại phòng đang được sử dụng không được xóa

                HƯỚNG DẪN TRẢ LỜI:
                - Ưu tiên dùng dữ liệu thực tế từ hệ thống (nếu có trong câu hỏi)
                - Trả lời ngắn gọn, rõ ràng, có số liệu cụ thể
                - Nếu câu hỏi về quy trình: giải thích từng bước
                - Nếu không chắc: hướng dẫn nhân viên kiểm tra trực tiếp trên app
                - KHÔNG bịa đặt thông tin không có trong hệ thống
                """;
    }

    // ============================================================
    // DỮ LIỆU THỰC TẾ TỪ DATABASE (ngắn gọn hơn)
    // ============================================================
    private String buildRealtimeData() {
        StringBuilder data = new StringBuilder();
        data.append("=== DỮ LIỆU THỰC TẾ HỆ THỐNG (").append(LocalDate.now()).append(") ===\n\n");

        // Phòng trống
        try {
            List<Room> availableRooms = roomRepository.findByStatus(
                    com.nguyenhuuquang.hotelmanagement.entity.enums.RoomStatus.AVAILABLE);

            long occupied = roomRepository.countByStatus(
                    com.nguyenhuuquang.hotelmanagement.entity.enums.RoomStatus.OCCUPIED);
            long reserved = roomRepository.countByStatus(
                    com.nguyenhuuquang.hotelmanagement.entity.enums.RoomStatus.RESERVED);
            long cleaning = roomRepository.countByStatus(
                    com.nguyenhuuquang.hotelmanagement.entity.enums.RoomStatus.CLEANING);
            long total = roomRepository.count();

            data.append("TỔNG QUAN PHÒNG: Tổng=").append(total)
                    .append(" | Trống=").append(availableRooms.size())
                    .append(" | Đang ở=").append(occupied)
                    .append(" | Đã đặt=").append(reserved)
                    .append(" | Dọn dẹp=").append(cleaning).append("\n");

            if (!availableRooms.isEmpty()) {
                data.append("PHÒNG TRỐNG:\n");
                for (Room room : availableRooms) {
                    data.append(String.format("  - Phòng %s | %s | %,.0fđ/đêm | Tầng %d\n",
                            room.getRoomNumber(),
                            room.getRoomType() != null ? room.getRoomType().getName() : "N/A",
                            room.getPrice(),
                            room.getFloor()));
                }
            } else {
                data.append("PHÒNG TRỐNG: Hiện không có phòng trống\n");
            }
        } catch (Exception e) {
            log.warn("⚠️ Error loading rooms: {}", e.getMessage());
        }

        // Loại phòng
        try {
            List<RoomType> roomTypes = roomTypeRepository.findAll();
            if (!roomTypes.isEmpty()) {
                data.append("LOẠI PHÒNG:\n");
                for (RoomType type : roomTypes) {
                    data.append(String.format("  - %s: %,.0fđ/đêm | Tối đa %d khách\n",
                            type.getName(),
                            type.getBasePrice(),
                            type.getMaxOccupancy() != null ? type.getMaxOccupancy() : 0));
                }
            }
        } catch (Exception e) {
            log.warn("⚠️ Error loading room types: {}", e.getMessage());
        }

        // Khuyến mãi đang chạy
        try {
            LocalDate today = LocalDate.now();
            List<Promotion> activePromotions = promotionRepository.findByActive(true).stream()
                    .filter(p -> (p.getStartDate() == null || !p.getStartDate().isAfter(today)) &&
                            (p.getEndDate() == null || !p.getEndDate().isBefore(today)))
                    .collect(Collectors.toList());

            if (!activePromotions.isEmpty()) {
                data.append("KHUYẾN MÃI ĐANG ÁP DỤNG:\n");
                for (Promotion promo : activePromotions) {
                    String discount = promo.getType() != null && promo.getValue() != null
                            ? (promo.getType().toString().equals("PERCENTAGE")
                                    ? promo.getValue() + "%"
                                    : String.format("%,.0fđ", promo.getValue()))
                            : "N/A";
                    data.append(String.format("  - [%s] %s: Giảm %s | HSD: %s\n",
                            promo.getCode(), promo.getName(), discount, promo.getEndDate()));
                }
            } else {
                data.append("KHUYẾN MÃI: Hiện không có khuyến mãi\n");
            }
        } catch (Exception e) {
            log.warn("⚠️ Error loading promotions: {}", e.getMessage());
        }

        // Thống kê booking hôm nay
        try {
            long pendingCount = bookingRepository
                    .findByStatus(com.nguyenhuuquang.hotelmanagement.entity.enums.BookingStatus.PENDING).size();
            long checkedInCount = bookingRepository
                    .findByStatus(com.nguyenhuuquang.hotelmanagement.entity.enums.BookingStatus.CHECKED_IN).size();
            long checkedOutCount = bookingRepository
                    .findByStatus(com.nguyenhuuquang.hotelmanagement.entity.enums.BookingStatus.CHECKED_OUT).size();

            data.append(String.format("BOOKING: Chờ xác nhận=%d | Đang ở=%d | Cần dọn=%d\n",
                    pendingCount, checkedInCount, checkedOutCount));
        } catch (Exception e) {
            log.warn("⚠️ Error loading booking stats: {}", e.getMessage());
        }

        return data.toString();
    }

    @SuppressWarnings("unchecked")
    private String callGeminiAPI(String systemInstruction, String userMessage) {
        try {
            // Sử dụng gemini-1.5-flash hoặc gemini-pro tùy URL config
            String url = geminiApiUrl + "?key=" + geminiApiKey;
            log.info("🌐 Calling Gemini API");

            Map<String, Object> requestBody = new HashMap<>();

            // System instruction
            Map<String, Object> sysInstruction = new HashMap<>();
            Map<String, String> sysPart = new HashMap<>();
            sysPart.put("text", systemInstruction);
            sysInstruction.put("parts", List.of(sysPart));
            requestBody.put("systemInstruction", sysInstruction);

            // User message (contents)
            List<Map<String, Object>> contents = new ArrayList<>();
            Map<String, Object> content = new HashMap<>();
            List<Map<String, String>> parts = new ArrayList<>();
            Map<String, String> part = new HashMap<>();
            part.put("text", userMessage);
            parts.add(part);
            content.put("role", "user");
            content.put("parts", parts);
            contents.add(content);
            requestBody.put("contents", contents);

            // Generation config - cho phép trả lời đầy đủ hơn
            Map<String, Object> generationConfig = new HashMap<>();
            generationConfig.put("maxOutputTokens", 2048);
            generationConfig.put("temperature", 0.2); // Thấp hơn = chính xác hơn
            generationConfig.put("topP", 0.8);
            requestBody.put("generationConfig", generationConfig);

            // Safety settings - tắt các filter không cần thiết
            List<Map<String, String>> safetySettings = new ArrayList<>();
            String[] harmCategories = {
                    "HARM_CATEGORY_HARASSMENT",
                    "HARM_CATEGORY_HATE_SPEECH",
                    "HARM_CATEGORY_SEXUALLY_EXPLICIT",
                    "HARM_CATEGORY_DANGEROUS_CONTENT"
            };
            for (String category : harmCategories) {
                Map<String, String> setting = new HashMap<>();
                setting.put("category", category);
                setting.put("threshold", "BLOCK_NONE");
                safetySettings.add(setting);
            }
            requestBody.put("safetySettings", safetySettings);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);

            ResponseEntity<Map> response = restTemplate.exchange(
                    url, HttpMethod.POST, entity, Map.class);

            log.info("✅ Gemini API responded with status: {}", response.getStatusCode());

            Map<String, Object> responseBody = response.getBody();
            if (responseBody != null && responseBody.containsKey("candidates")) {
                List<Map<String, Object>> candidates = (List<Map<String, Object>>) responseBody.get("candidates");
                if (!candidates.isEmpty()) {
                    Map<String, Object> candidate = candidates.get(0);

                    // Kiểm tra finishReason
                    String finishReason = (String) candidate.get("finishReason");
                    log.info("📊 Finish reason: {}", finishReason);

                    Map<String, Object> contentMap = (Map<String, Object>) candidate.get("content");
                    if (contentMap != null) {
                        List<Map<String, String>> partsList = (List<Map<String, String>>) contentMap.get("parts");
                        if (partsList != null && !partsList.isEmpty()) {
                            String result = partsList.get(0).get("text");
                            if (result != null && !result.trim().isEmpty()) {
                                return result;
                            }
                        }
                    }
                }
            }

            log.warn("⚠️ Empty response from Gemini, responseBody: {}", responseBody);
            return "Xin lỗi, tôi chưa xử lý được câu hỏi này. Vui lòng thử lại hoặc liên hệ quản lý.";

        } catch (HttpClientErrorException e) {
            log.error("❌ Gemini API Client Error: Status={}, Body={}",
                    e.getStatusCode(), e.getResponseBodyAsString());
            return switch (e.getStatusCode().value()) {
                case 400 -> "Yêu cầu không hợp lệ. Vui lòng thử lại với câu hỏi khác.";
                case 403 -> "API key không hợp lệ. Vui lòng liên hệ quản trị viên.";
                case 429 -> "Hệ thống AI đang quá tải. Vui lòng thử lại sau vài giây.";
                default -> "Lỗi khi xử lý yêu cầu (mã " + e.getStatusCode().value() + "). Vui lòng thử lại.";
            };
        } catch (HttpServerErrorException e) {
            log.error("❌ Gemini API Server Error: {}", e.getStatusCode());
            return "Máy chủ AI đang gặp sự cố. Vui lòng thử lại sau.";
        } catch (Exception e) {
            log.error("❌ Unexpected error calling Gemini: ", e);
            return "Đã xảy ra lỗi kết nối. Vui lòng thử lại sau.";
        }
    }

    public List<ChatResponse> getChatHistory(String userId) {
        try {
            List<ChatMessage> messages = chatMessageRepository.findByUserIdOrderByCreatedAtDesc(userId);
            return messages.stream()
                    .map(msg -> ChatResponse.builder()
                            .id(msg.getId())
                            .userMessage(msg.getUserMessage())
                            .aiResponse(msg.getAiResponse())
                            .timestamp(msg.getCreatedAt())
                            .build())
                    .toList();
        } catch (Exception e) {
            log.error("❌ Error fetching chat history: ", e);
            return new ArrayList<>();
        }
    }
}