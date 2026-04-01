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
import com.nguyenhuuquang.hotelmanagement.entity.Booking;
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

            String systemContext = buildSystemContext();
            String businessKnowledge = buildBusinessKnowledge();

            String fullPrompt = systemContext + "\n\n"
                    + businessKnowledge + "\n\n"
                    + "Câu hỏi: " + request.getMessage() + "\n\n"
                    + "Hãy trả lời ngắn gọn, chính xác bằng tiếng Việt dựa trên thông tin hệ thống và nghiệp vụ ở trên.";

            String aiResponse = callGeminiAPI(fullPrompt);

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
    // DỮ LIỆU THỰC TẾ TỪ DATABASE
    // ============================================================
    private String buildSystemContext() {
        StringBuilder context = new StringBuilder();
        context.append("=== DỮ LIỆU THỰC TẾ HỆ THỐNG (CẬP NHẬT THEO THỜI GIAN THỰC) ===\n\n");

        // Phòng trống
        try {
            List<Room> availableRooms = roomRepository.findByStatus(
                    com.nguyenhuuquang.hotelmanagement.entity.enums.RoomStatus.AVAILABLE);
            context.append("📊 PHÒNG TRỐNG HIỆN TẠI (").append(availableRooms.size()).append(" phòng):\n");
            if (availableRooms.isEmpty()) {
                context.append("- Hiện tại không có phòng trống.\n");
            } else {
                for (Room room : availableRooms) {
                    context.append(String.format("  • Phòng %s | Loại: %s | Giá: %,.0f VNĐ/đêm | Tầng %d\n",
                            room.getRoomNumber(),
                            room.getRoomType() != null ? room.getRoomType().getName() : "N/A",
                            room.getPrice(),
                            room.getFloor()));
                }
            }
            context.append("\n");
        } catch (Exception e) {
            log.warn("⚠️ Error loading available rooms: {}", e.getMessage());
        }

        // Tất cả phòng theo trạng thái
        try {
            long occupied = roomRepository.countByStatus(
                    com.nguyenhuuquang.hotelmanagement.entity.enums.RoomStatus.OCCUPIED);
            long reserved = roomRepository.countByStatus(
                    com.nguyenhuuquang.hotelmanagement.entity.enums.RoomStatus.RESERVED);
            long cleaning = roomRepository.countByStatus(
                    com.nguyenhuuquang.hotelmanagement.entity.enums.RoomStatus.CLEANING);
            long maintenance = roomRepository.countByStatus(
                    com.nguyenhuuquang.hotelmanagement.entity.enums.RoomStatus.MAINTENANCE);
            long total = roomRepository.count();

            context.append("🏨 TỔNG QUAN PHÒNG:\n");
            context.append(
                    String.format("  • Tổng: %d phòng | Đang ở: %d | Đã đặt: %d | Đang dọn: %d | Bảo trì: %d\n\n",
                            total, occupied, reserved, cleaning, maintenance));
        } catch (Exception e) {
            log.warn("⚠️ Error loading room stats: {}", e.getMessage());
        }

        // Loại phòng
        try {
            List<RoomType> roomTypes = roomTypeRepository.findAll();
            context.append("🏷️ CÁC LOẠI PHÒNG:\n");
            for (RoomType type : roomTypes) {
                context.append(String.format("  • %s: %,.0f VNĐ/đêm | Sức chứa: %d khách | %s\n",
                        type.getName(),
                        type.getBasePrice(),
                        type.getMaxOccupancy() != null ? type.getMaxOccupancy() : 0,
                        type.getDescription() != null ? type.getDescription() : ""));
            }
            context.append("\n");
        } catch (Exception e) {
            log.warn("⚠️ Error loading room types: {}", e.getMessage());
        }

        // Khuyến mãi
        try {
            LocalDate today = LocalDate.now();
            List<Promotion> activePromotions = promotionRepository.findByActive(true).stream()
                    .filter(p -> (p.getStartDate() == null || !p.getStartDate().isAfter(today)) &&
                            (p.getEndDate() == null || !p.getEndDate().isBefore(today)))
                    .collect(Collectors.toList());

            context.append("🎁 KHUYẾN MÃI ĐANG ÁP DỤNG:\n");
            if (activePromotions.isEmpty()) {
                context.append("  • Hiện tại không có chương trình khuyến mãi.\n");
            } else {
                for (Promotion promo : activePromotions) {
                    String discountInfo = "";
                    if (promo.getType() != null && promo.getValue() != null) {
                        discountInfo = promo.getType().toString().equals("PERCENTAGE")
                                ? promo.getValue() + "% (tối đa " + (promo.getMaxDiscount() != null
                                        ? String.format("%,.0f VNĐ", promo.getMaxDiscount())
                                        : "không giới hạn") + ")"
                                : String.format("%,.0f VNĐ", promo.getValue());
                    }
                    context.append(String.format("  • [%s] %s: Giảm %s | Đơn tối thiểu: %s | HSD: %s\n",
                            promo.getCode(), promo.getName(), discountInfo,
                            promo.getMinBookingAmount() != null
                                    ? String.format("%,.0f VNĐ", promo.getMinBookingAmount())
                                    : "Không yêu cầu",
                            promo.getEndDate()));
                }
            }
            context.append("\n");
        } catch (Exception e) {
            log.warn("⚠️ Error loading promotions: {}", e.getMessage());
        }

        // Thống kê hôm nay
        try {
            LocalDate today = LocalDate.now();
            List<Booking> todayCheckIns = bookingRepository.findAll().stream()
                    .filter(b -> b.getCheckIn() != null && b.getCheckIn().equals(today))
                    .collect(Collectors.toList());
            List<Booking> todayCheckOuts = bookingRepository.findAll().stream()
                    .filter(b -> b.getCheckOut() != null && b.getCheckOut().equals(today))
                    .collect(Collectors.toList());
            long pendingCount = bookingRepository.findByStatus(
                    com.nguyenhuuquang.hotelmanagement.entity.enums.BookingStatus.PENDING).size();
            long checkedInCount = bookingRepository.findByStatus(
                    com.nguyenhuuquang.hotelmanagement.entity.enums.BookingStatus.CHECKED_IN).size();

            context.append("📅 THỐNG KÊ HÔM NAY (").append(today).append("):\n");
            context.append(String.format("  • Check-in hôm nay: %d | Check-out hôm nay: %d\n",
                    todayCheckIns.size(), todayCheckOuts.size()));
            context.append(String.format("  • Đang chờ xác nhận: %d | Đang ở: %d\n\n",
                    pendingCount, checkedInCount));
        } catch (Exception e) {
            log.warn("⚠️ Error loading booking stats: {}", e.getMessage());
        }

        return context.toString();
    }

    // ============================================================
    // KIẾN THỨC NGHIỆP VỤ KHÁCH SẠN
    // ============================================================
    private String buildBusinessKnowledge() {
        return """
                === KIẾN THỨC NGHIỆP VỤ HỆ THỐNG QUẢN LÝ KHÁCH SẠN ===

                ## QUY TRÌNH ĐẶT PHÒNG (BOOKING FLOW)
                Luồng trạng thái booking: PENDING → CONFIRMED → CHECKED_IN → CHECKED_OUT → COMPLETED
                Trạng thái đặc biệt: CANCELLED (hủy), NO_SHOW (khách không đến)

                Chi tiết từng bước:
                1. PENDING (Chờ xác nhận): Booking vừa được tạo, chờ lễ tân xác nhận. Phòng chuyển sang RESERVED.
                2. CONFIRMED (Đã xác nhận): Lễ tân đã xác nhận. Chỉ xác nhận được khi phòng không có khách CHECKED_IN.
                3. CHECKED_IN (Đã nhận phòng): Khách đã đến và nhận phòng. Phòng chuyển sang OCCUPIED.
                4. CHECKED_OUT (Đã trả phòng): Khách trả phòng. Phòng chuyển sang CLEANING.
                5. COMPLETED (Hoàn thành): Thanh toán xong, phòng chuyển về AVAILABLE.
                6. CANCELLED: Hủy booking, phòng trả về AVAILABLE (nếu không có khách khác).
                7. NO_SHOW: Khách không đến sau khi đã CONFIRMED.

                ## TRẠNG THÁI PHÒNG (ROOM STATUS)
                • AVAILABLE: Phòng trống, sẵn sàng nhận khách
                • RESERVED: Đã có booking đặt nhưng chưa check-in
                • OCCUPIED: Đang có khách ở
                • CLEANING: Đang dọn dẹp sau khi khách trả phòng
                • MAINTENANCE: Đang bảo trì, không cho thuê
                • OUT_OF_ORDER: Hỏng, ngừng hoạt động

                ## TÍNH TIỀN PHÒNG
                Công thức: Tổng tiền = (Giá phòng × Số đêm) + Tiền dịch vụ - Giảm giá
                • Số đêm = Ngày check-out - Ngày check-in
                • Tiền dịch vụ: Cộng dồn từ các dịch vụ phát sinh trong booking
                • Giảm giá: Tổng discount từ các mã khuyến mãi được áp dụng
                • Đặt cọc (Deposit): Khoản tiền thu trước, trừ vào tổng khi thanh toán

                ## QUY TẮC THANH TOÁN
                • Thanh toán cọc: Thu khi xác nhận booking (thường 30% tổng tiền)
                • Thanh toán hoàn tất: Thu khi check-out (số tiền còn lại = Tổng - Cọc đã trả)
                • Phương thức: CASH (tiền mặt), CARD (thẻ), TRANSFER (chuyển khoản)
                • Tích hợp PayOS: Thanh toán online qua QR/link cho cả 2 luồng cọc và checkout

                ## MÃ KHUYẾN MÃI (PROMOTION)
                Loại khuyến mãi:
                • PERCENTAGE: Giảm theo % (VD: giảm 10%, tối đa 200.000 VNĐ)
                • FIXED_AMOUNT: Giảm số tiền cố định (VD: giảm 100.000 VNĐ)
                Điều kiện áp dụng:
                • Mã phải còn hiệu lực (trong khoảng startDate - endDate)
                • Giá trị booking phải đạt mức tối thiểu (minBookingAmount)
                • Mã chưa vượt quá số lần sử dụng tối đa (maxUsage)
                • Mỗi booking có thể áp dụng nhiều mã khuyến mãi

                ## DỊCH VỤ PHÒNG (ROOM SERVICES)
                • Dịch vụ được thêm vào booking bất kỳ lúc nào khi booking chưa COMPLETED/CANCELLED
                • Mỗi dịch vụ có: tên, danh mục (category), đơn giá, số lượng
                • Tiền dịch vụ tự động cộng vào tổng tiền booking
                • Có thể cập nhật số lượng hoặc xóa dịch vụ khỏi booking

                ## ĐỔI PHÒNG (CHANGE ROOM)
                • Chỉ đổi được khi booking ở trạng thái PENDING hoặc CONFIRMED
                • Phòng mới phải ở trạng thái AVAILABLE
                • Phòng mới không được có booking CHECKED_IN trùng thời gian
                • Tiền phòng tự động tính lại theo giá phòng mới

                ## QUẢN LÝ TÀI CHÍNH
                Giao dịch (Transaction) gồm 2 loại:
                • INCOME (Thu): Tiền phòng, dịch vụ, đặt cọc từ khách
                • EXPENSE (Chi): Chi phí vận hành, lương, vật tư...
                Báo cáo: Lọc theo ngày/tuần/tháng/năm, xem lợi nhuận = Thu - Chi

                ## LỊCH SỬ CHECK-OUT (CHECKOUT HISTORY)
                • Lưu lại toàn bộ thông tin sau khi booking COMPLETED
                • Dùng để phân tích: Doanh thu theo tháng, khách VIP, hiệu suất từng phòng
                • Khách VIP: Khách có số lần ở ≥ ngưỡng và tổng chi tiêu ≥ ngưỡng

                ## QUY TẮC NGHIỆP VỤ QUAN TRỌNG
                1. Một phòng có thể có nhiều booking chồng thời gian (booking tương lai) nhưng chỉ 1 CHECKED_IN cùng lúc
                2. Khi hủy booking đang CHECKED_IN: không được hủy, phải checkout trước
                3. NO_SHOW không hoàn tiền cọc theo quy định
                4. Phòng đang OCCUPIED hoặc RESERVED không được xóa
                5. Loại phòng đang được dùng bởi phòng nào đó không được xóa

                ## LUỒNG THỰC HÀNH ĐỐI VỚI NHÂN VIÊN
                Check-in khách:
                  1. Tìm booking → Xác nhận thông tin khách và ngày
                  2. Bấm CONFIRM nếu booking còn PENDING
                  3. Bấm CHECK-IN khi khách đến
                  4. Thu tiền cọc nếu chưa thu

                Check-out khách:
                  1. Tìm booking đang CHECKED_IN
                  2. Kiểm tra các dịch vụ phát sinh
                  3. Tính tổng tiền còn lại (Tổng - Cọc đã trả)
                  4. Thu tiền → Bấm CHECK-OUT → Bấm COMPLETE
                  5. Tạo lịch sử checkout

                Xử lý khách hủy:
                  1. Nếu PENDING/CONFIRMED: Bấm CANCEL, hoàn tiền cọc nếu có
                  2. Nếu khách không đến: Bấm NO-SHOW, không hoàn cọc
                  3. Không thể hủy booking đã COMPLETED

                === HƯỚNG DẪN TRẢ LỜI ===
                - Bạn là trợ lý AI thông minh cho nhân viên khách sạn
                - Ưu tiên trả lời dựa trên DỮ LIỆU THỰC TẾ ở trên trước
                - Với câu hỏi nghiệp vụ/quy trình: dùng KIẾN THỨC NGHIỆP VỤ ở trên
                - Trả lời ngắn gọn, thực tế, có ví dụ cụ thể khi cần
                - KHÔNG bịa đặt thông tin không có trong hệ thống
                - Nếu không chắc: hướng dẫn nhân viên kiểm tra trực tiếp trên app
                """;
    }

    @SuppressWarnings("unchecked")
    private String callGeminiAPI(String prompt) {
        try {
            String url = geminiApiUrl + "?key=" + geminiApiKey;
            log.info("🌐 Calling Gemini API");

            Map<String, Object> requestBody = new HashMap<>();
            List<Map<String, Object>> contents = new ArrayList<>();
            Map<String, Object> content = new HashMap<>();
            List<Map<String, String>> parts = new ArrayList<>();
            Map<String, String> part = new HashMap<>();
            part.put("text", prompt);
            parts.add(part);
            content.put("parts", parts);
            contents.add(content);
            requestBody.put("contents", contents);

            // Cấu hình generation để trả lời ngắn gọn hơn
            Map<String, Object> generationConfig = new HashMap<>();
            generationConfig.put("maxOutputTokens", 1024);
            generationConfig.put("temperature", 0.3);
            requestBody.put("generationConfig", generationConfig);

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
                    Map<String, Object> contentMap = (Map<String, Object>) candidate.get("content");
                    List<Map<String, String>> partsList = (List<Map<String, String>>) contentMap.get("parts");
                    if (!partsList.isEmpty()) {
                        return partsList.get(0).get("text");
                    }
                }
            }

            return "Xin lỗi, tôi không thể trả lời câu hỏi này lúc này.";

        } catch (HttpClientErrorException e) {
            log.error("❌ Gemini API Client Error: Status={}, Body={}",
                    e.getStatusCode(), e.getResponseBodyAsString());
            return switch (e.getStatusCode().value()) {
                case 400 -> "Yêu cầu không hợp lệ. Vui lòng thử lại.";
                case 403 -> "API key không hợp lệ. Vui lòng liên hệ quản trị viên.";
                case 429 -> "Hệ thống AI đang quá tải. Vui lòng thử lại sau.";
                default -> "Lỗi khi xử lý yêu cầu. Vui lòng thử lại.";
            };
        } catch (HttpServerErrorException e) {
            log.error("❌ Gemini API Server Error: {}", e.getStatusCode());
            return "Máy chủ AI đang gặp sự cố. Vui lòng thử lại sau.";
        } catch (Exception e) {
            log.error("❌ Unexpected error: ", e);
            return "Đã xảy ra lỗi. Vui lòng thử lại sau.";
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