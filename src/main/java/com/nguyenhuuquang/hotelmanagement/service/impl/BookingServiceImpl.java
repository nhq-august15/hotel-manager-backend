package com.nguyenhuuquang.hotelmanagement.service.impl;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.nguyenhuuquang.hotelmanagement.dto.BookingDTO;
import com.nguyenhuuquang.hotelmanagement.dto.BookingWithServicesDTO;
import com.nguyenhuuquang.hotelmanagement.dto.CreateBookingRequest;
import com.nguyenhuuquang.hotelmanagement.dto.DashboardStatsDTO;
import com.nguyenhuuquang.hotelmanagement.entity.Booking;
import com.nguyenhuuquang.hotelmanagement.entity.BookingPromotion;
import com.nguyenhuuquang.hotelmanagement.entity.Promotion;
import com.nguyenhuuquang.hotelmanagement.entity.Room;
import com.nguyenhuuquang.hotelmanagement.entity.enums.BookingStatus;
import com.nguyenhuuquang.hotelmanagement.entity.enums.LogType;
import com.nguyenhuuquang.hotelmanagement.entity.enums.RoomStatus;
import com.nguyenhuuquang.hotelmanagement.exception.ResourceNotFoundException;
import com.nguyenhuuquang.hotelmanagement.repository.BookingPromotionRepository;
import com.nguyenhuuquang.hotelmanagement.repository.BookingRepository;
import com.nguyenhuuquang.hotelmanagement.repository.PromotionRepository;
import com.nguyenhuuquang.hotelmanagement.repository.RoomRepository;
import com.nguyenhuuquang.hotelmanagement.repository.ServiceRepository;
import com.nguyenhuuquang.hotelmanagement.service.BookingService;
import com.nguyenhuuquang.hotelmanagement.service.PromotionService;
import com.nguyenhuuquang.hotelmanagement.service.SystemLogService;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class BookingServiceImpl implements BookingService {

        private final BookingRepository bookingRepo;
        private final RoomRepository roomRepo;
        private final SystemLogService logService;
        private final ServiceRepository serviceRepo;

        private final PromotionRepository promotionRepo;
        private final BookingPromotionRepository bookingPromotionRepo;
        private final PromotionService promotionService;

        @Override
        @Transactional
        public BookingDTO createBooking(CreateBookingRequest request) {
                Room room = roomRepo.findById(request.getRoomId())
                                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy phòng"));

                long nights = ChronoUnit.DAYS.between(request.getCheckIn(), request.getCheckOut());
                if (nights <= 0) {
                        throw new IllegalArgumentException("Ngày trả phòng phải sau ngày nhận phòng");
                }

                List<Booking> overlappingBookings = bookingRepo.findOverlappingBookings(
                                request.getRoomId(),
                                request.getCheckIn(),
                                request.getCheckOut());

                boolean hasCheckedInBooking = overlappingBookings.stream()
                                .anyMatch(b -> b.getStatus() == BookingStatus.CHECKED_IN);

                BigDecimal roomAmount = room.getPrice().multiply(BigDecimal.valueOf(nights));
                BigDecimal serviceAmount = BigDecimal.ZERO;
                BigDecimal discountAmount = BigDecimal.ZERO;
                BigDecimal totalAmount = roomAmount.add(serviceAmount);

                BigDecimal deposit = request.getDeposit() != null ? request.getDeposit() : BigDecimal.ZERO;

                BookingStatus initialStatus = BookingStatus.PENDING;

                Booking booking = Booking.builder()
                                .room(room)
                                .customerName(request.getCustomerName())
                                .phone(request.getPhone())
                                .checkIn(request.getCheckIn())
                                .checkOut(request.getCheckOut())
                                .nights((int) nights)
                                .numberOfGuests(1)
                                .roomAmount(roomAmount)
                                .serviceAmount(serviceAmount)
                                .discountAmount(discountAmount)
                                .totalAmount(totalAmount)
                                .deposit(deposit)
                                .paidAmount(BigDecimal.ZERO)
                                .status(initialStatus)
                                .notes(request.getNotes())
                                .createdBy(1L)
                                .build();

                booking = bookingRepo.save(booking);

                // ✨ Áp dụng promotion nếu có
                if (request.getPromotionCode() != null && !request.getPromotionCode().trim().isEmpty()) {
                        try {
                                Promotion promotion = promotionRepo.findValidPromotionByCode(
                                                request.getPromotionCode().toUpperCase(),
                                                LocalDate.now())
                                                .orElseThrow(() -> new IllegalArgumentException(
                                                                "Mã khuyến mãi không hợp lệ hoặc đã hết hạn"));

                                BigDecimal discount = promotionService.calculateDiscount(
                                                request.getPromotionCode(),
                                                booking.getTotalAmount());

                                BookingPromotion bookingPromotion = BookingPromotion.builder()
                                                .promotion(promotion)
                                                .discountAmount(discount)
                                                .promotionCode(promotion.getCode())
                                                .promotionName(promotion.getName())
                                                .build();

                                booking.addBookingPromotion(bookingPromotion);
                                promotion.incrementUsage();
                                promotionRepo.save(promotion);

                                booking.recalculateTotalAmount();
                                booking = bookingRepo.save(booking);

                                logService.log(LogType.SUCCESS, "Áp dụng mã giảm giá", "Admin",
                                                String.format("Đã áp dụng mã %s cho booking #%d",
                                                                promotion.getCode(), booking.getId()),
                                                String.format("Giảm giá: %s", discount));
                        } catch (Exception e) {
                                logService.log(LogType.WARNING, "Lỗi áp dụng mã giảm giá", "Admin",
                                                e.getMessage());
                        }
                }

                if (!hasCheckedInBooking && room.getStatus() == RoomStatus.AVAILABLE) {
                        room.setStatus(RoomStatus.RESERVED);
                        roomRepo.save(room);
                }

                String statusNote = hasCheckedInBooking
                                ? "Chờ xác nhận (Phòng đang có khách)"
                                : "Chờ xác nhận";

                logService.log(LogType.SUCCESS, "Tạo đặt phòng", "Admin",
                                String.format("Đã tạo đặt phòng %s cho khách %s",
                                                room.getRoomNumber(), request.getCustomerName()),
                                String.format("Phòng %s, %d đêm, tiền phòng %s, giảm giá %s, tổng tiền %s, trạng thái: %s",
                                                room.getRoomNumber(), nights, roomAmount,
                                                booking.getDiscountAmount(), booking.getTotalAmount(), statusNote));

                return convertToDTO(booking);
        }

        @Override
        @Transactional
        public BookingDTO addServiceToBooking(Long bookingId, Long serviceId, Integer quantity) {
                Booking booking = bookingRepo.findById(bookingId)
                                .orElseThrow(() -> new ResourceNotFoundException(
                                                "Không tìm thấy đặt phòng với ID: " + bookingId));

                if (booking.getStatus() == BookingStatus.COMPLETED || booking.getStatus() == BookingStatus.CANCELLED) {
                        throw new IllegalStateException(
                                        "Không thể thêm dịch vụ cho đặt phòng đã hoàn thành hoặc đã hủy.");
                }

                com.nguyenhuuquang.hotelmanagement.entity.Service service = serviceRepo.findById(serviceId)
                                .orElseThrow(() -> new ResourceNotFoundException(
                                                "Không tìm thấy dịch vụ với ID: " + serviceId));

                BigDecimal unitPrice = service.getPrice();
                BigDecimal totalPrice = unitPrice.multiply(BigDecimal.valueOf(quantity));

                com.nguyenhuuquang.hotelmanagement.entity.BookingService bs = com.nguyenhuuquang.hotelmanagement.entity.BookingService
                                .builder()
                                .service(service)
                                .quantity(quantity)
                                .unitPrice(unitPrice)
                                .totalPrice(totalPrice)
                                .serviceDate(LocalDateTime.now())
                                .build();

                booking.addBookingService(bs);
                booking.recalculateTotalAmount();
                booking = bookingRepo.save(booking);

                logService.log(LogType.SUCCESS, "Thêm dịch vụ", "Admin",
                                String.format("Đã thêm %d x %s cho phòng %s",
                                                quantity, service.getName(), booking.getRoom().getRoomNumber()),
                                String.format("Giá trị thêm: %s. Tổng hóa đơn mới: %s",
                                                totalPrice, booking.getTotalAmount()));

                return convertToDTO(booking);
        }

        @Override
        @Transactional
        public BookingDTO applyPromotionToBooking(Long bookingId, String promotionCode) {
                Booking booking = bookingRepo.findById(bookingId)
                                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy đặt phòng"));

                if (booking.getStatus() == BookingStatus.COMPLETED ||
                                booking.getStatus() == BookingStatus.CANCELLED) {
                        throw new IllegalStateException(
                                        "Không thể áp dụng mã giảm giá cho đặt phòng đã hoàn thành hoặc đã hủy");
                }

                // Kiểm tra xem booking đã có promotion này chưa
                boolean hasPromotion = booking.getBookingPromotions().stream()
                                .anyMatch(bp -> bp.getPromotionCode().equalsIgnoreCase(promotionCode));

                if (hasPromotion) {
                        throw new IllegalArgumentException("Mã giảm giá này đã được áp dụng cho booking này");
                }

                Promotion promotion = promotionRepo.findValidPromotionByCode(
                                promotionCode.toUpperCase(),
                                LocalDate.now())
                                .orElseThrow(() -> new IllegalArgumentException(
                                                "Mã khuyến mãi không hợp lệ hoặc đã hết hạn"));

                // Tính discount dựa trên tổng tiền hiện tại (đã bao gồm dịch vụ nhưng chưa trừ
                // discount)
                BigDecimal currentSubtotal = booking.getRoomAmount().add(booking.getServiceAmount());

                BigDecimal discount = promotionService.calculateDiscount(
                                promotionCode,
                                currentSubtotal);

                BookingPromotion bookingPromotion = BookingPromotion.builder()
                                .promotion(promotion)
                                .discountAmount(discount)
                                .promotionCode(promotion.getCode())
                                .promotionName(promotion.getName())
                                .build();

                booking.addBookingPromotion(bookingPromotion);
                promotion.incrementUsage();
                promotionRepo.save(promotion);

                booking.recalculateTotalAmount();
                booking = bookingRepo.save(booking);

                logService.log(LogType.SUCCESS, "Áp dụng mã giảm giá", "Admin",
                                String.format("Đã áp dụng mã %s cho booking #%d",
                                                promotion.getCode(), booking.getId()),
                                String.format("Giảm giá: %s. Tổng tiền mới: %s",
                                                discount, booking.getTotalAmount()));

                return convertToDTO(booking);
        }

        @Override
        @Transactional
        public BookingDTO removePromotionFromBooking(Long bookingId, Long promotionId) {
                Booking booking = bookingRepo.findById(bookingId)
                                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy đặt phòng"));

                if (booking.getStatus() == BookingStatus.COMPLETED ||
                                booking.getStatus() == BookingStatus.CANCELLED) {
                        throw new IllegalStateException(
                                        "Không thể xóa mã giảm giá cho đặt phòng đã hoàn thành hoặc đã hủy");
                }

                BookingPromotion bookingPromotion = booking.getBookingPromotions().stream()
                                .filter(bp -> bp.getId().equals(promotionId))
                                .findFirst()
                                .orElseThrow(() -> new ResourceNotFoundException(
                                                "Không tìm thấy mã giảm giá trong booking này"));

                String promotionCode = bookingPromotion.getPromotionCode();
                BigDecimal discountAmount = bookingPromotion.getDiscountAmount();

                // Giảm usage count của promotion
                Promotion promotion = bookingPromotion.getPromotion();
                if (promotion.getUsedCount() > 0) {
                        promotion.setUsedCount(promotion.getUsedCount() - 1);
                        promotionRepo.save(promotion);
                }

                booking.removeBookingPromotion(bookingPromotion);
                booking.recalculateTotalAmount();
                booking = bookingRepo.save(booking);

                logService.log(LogType.INFO, "Xóa mã giảm giá", "Admin",
                                String.format("Đã xóa mã %s khỏi booking #%d",
                                                promotionCode, booking.getId()),
                                String.format("Hoàn lại giảm giá: %s. Tổng tiền mới: %s",
                                                discountAmount, booking.getTotalAmount()));

                return convertToDTO(booking);
        }

        @Override
        @Transactional
        public BookingDTO confirmBooking(Long bookingId) {
                Booking booking = bookingRepo.findById(bookingId)
                                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy đặt phòng"));

                if (booking.getStatus() != BookingStatus.PENDING) {
                        throw new IllegalStateException("Chỉ có thể xác nhận đặt phòng ở trạng thái chờ xác nhận");
                }

                // ✅ Kiểm tra xem phòng có booking CHECKED_IN nào không
                List<Booking> overlappingBookings = bookingRepo.findOverlappingBookings(
                                booking.getRoom().getId(),
                                booking.getCheckIn(),
                                booking.getCheckOut());

                boolean hasCheckedInBooking = overlappingBookings.stream()
                                .anyMatch(b -> b.getStatus() == BookingStatus.CHECKED_IN
                                                && !b.getId().equals(bookingId));

                if (hasCheckedInBooking) {
                        throw new IllegalStateException(
                                        "Không thể xác nhận. Phòng này đang có khách đang ở. Vui lòng đợi khách trả phòng trước.");
                }

                booking.setStatus(BookingStatus.CONFIRMED);
                booking = bookingRepo.save(booking);

                logService.log(LogType.SUCCESS, "Xác nhận đặt phòng", "Admin",
                                String.format("Đã xác nhận đặt phòng %s cho khách %s",
                                                booking.getRoom().getRoomNumber(), booking.getCustomerName()),
                                "Đặt phòng đã được xác nhận, chờ khách nhận phòng");

                return convertToDTO(booking);
        }

        @Override
        @Transactional
        public BookingDTO checkIn(Long bookingId) {
                Booking booking = bookingRepo.findById(bookingId)
                                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy đặt phòng"));

                if (booking.getStatus() != BookingStatus.CONFIRMED) {
                        throw new IllegalStateException("Chỉ có thể nhận phòng khi đặt phòng đã được xác nhận");
                }

                // ✅ Kiểm tra xem phòng có booking CHECKED_IN nào không
                List<Booking> overlappingBookings = bookingRepo.findOverlappingBookings(
                                booking.getRoom().getId(),
                                booking.getCheckIn(),
                                booking.getCheckOut());

                boolean hasCheckedInBooking = overlappingBookings.stream()
                                .anyMatch(b -> b.getStatus() == BookingStatus.CHECKED_IN
                                                && !b.getId().equals(bookingId));

                if (hasCheckedInBooking) {
                        throw new IllegalStateException(
                                        "Không thể nhận phòng. Phòng này đang có khách đang ở. Vui lòng đợi khách trả phòng trước.");
                }

                booking.setStatus(BookingStatus.CHECKED_IN);
                booking.setActualCheckIn(LocalDate.now());
                booking = bookingRepo.save(booking);

                Room room = booking.getRoom();
                room.setStatus(RoomStatus.OCCUPIED);
                roomRepo.save(room);

                logService.log(LogType.SUCCESS, "Nhận phòng", "Admin",
                                String.format("Khách %s đã nhận phòng %s",
                                                booking.getCustomerName(), room.getRoomNumber()),
                                "Check-in thành công, phòng đang được sử dụng");

                return convertToDTO(booking);
        }

        @Override
        @Transactional
        public BookingDTO checkOut(Long bookingId) {
                Booking booking = bookingRepo.findById(bookingId)
                                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy đặt phòng"));

                if (booking.getStatus() != BookingStatus.CHECKED_IN) {
                        throw new IllegalStateException("Chỉ có thể trả phòng khi khách đã nhận phòng");
                }

                booking.setStatus(BookingStatus.CHECKED_OUT);
                booking.setActualCheckOut(LocalDate.now());
                booking = bookingRepo.save(booking);

                Room room = booking.getRoom();
                room.setStatus(RoomStatus.CLEANING);
                roomRepo.save(room);

                logService.log(LogType.SUCCESS, "Trả phòng", "Admin",
                                String.format("Khách %s đã trả phòng %s",
                                                booking.getCustomerName(), room.getRoomNumber()),
                                "Check-out thành công, phòng chuyển sang trạng thái dọn dẹp. Booking PENDING tiếp theo có thể được xác nhận.");

                return convertToDTO(booking);
        }

        @Override
        @Transactional
        public BookingDTO completeBooking(Long bookingId) {
                Booking booking = bookingRepo.findById(bookingId)
                                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy đặt phòng"));

                if (booking.getStatus() != BookingStatus.CHECKED_OUT) {
                        throw new IllegalStateException("Chỉ có thể hoàn thành khi khách đã trả phòng");
                }

                booking.setStatus(BookingStatus.COMPLETED);
                booking = bookingRepo.save(booking);

                Room room = booking.getRoom();
                if (room.getStatus() == RoomStatus.CLEANING) {
                        room.setStatus(RoomStatus.AVAILABLE);
                        roomRepo.save(room);
                }

                logService.log(LogType.SUCCESS, "Hoàn thành đặt phòng", "Admin",
                                String.format("Đã hoàn thành đặt phòng %s", room.getRoomNumber()),
                                "Thanh toán đầy đủ, phòng sẵn sàng cho khách tiếp theo");

                return convertToDTO(booking);
        }

        @Override
        @Transactional
        public void cancelBooking(Long bookingId) {
                Booking booking = bookingRepo.findById(bookingId)
                                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy đặt phòng"));

                if (booking.getStatus() == BookingStatus.COMPLETED) {
                        throw new IllegalStateException("Không thể hủy đặt phòng đã hoàn thành");
                }

                if (booking.getStatus() == BookingStatus.CANCELLED) {
                        throw new IllegalStateException("Đặt phòng đã bị hủy trước đó");
                }

                BookingStatus oldStatus = booking.getStatus();
                booking.setStatus(BookingStatus.CANCELLED);
                booking.setCancelledAt(LocalDateTime.now());
                bookingRepo.save(booking);

                // ✅ Chỉ set AVAILABLE nếu không có booking CHECKED_IN nào khác
                Room room = booking.getRoom();
                List<Booking> activeBookings = bookingRepo.findActiveBookingsByRoom(room.getId());
                boolean hasOtherActiveBooking = activeBookings.stream()
                                .anyMatch(b -> !b.getId().equals(bookingId)
                                                && b.getStatus() == BookingStatus.CHECKED_IN);

                if (!hasOtherActiveBooking) {
                        room.setStatus(RoomStatus.AVAILABLE);
                        roomRepo.save(room);
                }

                logService.log(LogType.SUCCESS, "Hủy đặt phòng", "Admin",
                                String.format("Đã hủy đặt phòng %s (Trạng thái cũ: %s)",
                                                room.getRoomNumber(), oldStatus),
                                "Hoàn tiền cọc cho khách");
        }

        @Override
        @Transactional
        public void markNoShow(Long bookingId) {
                Booking booking = bookingRepo.findById(bookingId)
                                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy đặt phòng"));

                if (booking.getStatus() != BookingStatus.CONFIRMED) {
                        throw new IllegalStateException("Chỉ có thể đánh dấu No-Show cho đặt phòng đã xác nhận");
                }

                booking.setStatus(BookingStatus.NO_SHOW);
                booking.setCancelledAt(LocalDateTime.now());
                bookingRepo.save(booking);

                Room room = booking.getRoom();

                // ✅ Chỉ set AVAILABLE nếu không có booking CHECKED_IN nào khác
                List<Booking> activeBookings = bookingRepo.findActiveBookingsByRoom(room.getId());
                boolean hasOtherActiveBooking = activeBookings.stream()
                                .anyMatch(b -> !b.getId().equals(bookingId)
                                                && b.getStatus() == BookingStatus.CHECKED_IN);

                if (!hasOtherActiveBooking) {
                        room.setStatus(RoomStatus.AVAILABLE);
                        roomRepo.save(room);
                }

                logService.log(LogType.WARNING, "Khách không đến", "Admin",
                                String.format("Khách %s không đến nhận phòng %s",
                                                booking.getCustomerName(), room.getRoomNumber()),
                                "Không hoàn tiền cọc");
        }

        @Override
        public List<BookingDTO> getActiveBookings() {
                List<Booking> pendingBookings = bookingRepo.findByStatus(BookingStatus.PENDING);
                List<Booking> confirmedBookings = bookingRepo.findByStatus(BookingStatus.CONFIRMED);
                List<Booking> checkedInBookings = bookingRepo.findByStatus(BookingStatus.CHECKED_IN);

                List<Booking> allActiveBookings = new ArrayList<>();
                allActiveBookings.addAll(pendingBookings);
                allActiveBookings.addAll(confirmedBookings);
                allActiveBookings.addAll(checkedInBookings);

                return allActiveBookings.stream()
                                .map(this::convertToDTO)
                                .collect(Collectors.toList());
        }

        @Override
        public List<BookingDTO> getAllBookings() {
                return bookingRepo.findAll()
                                .stream()
                                .map(this::convertToDTO)
                                .collect(Collectors.toList());
        }

        @Override
        public List<BookingDTO> getBookingsByStatus(String status) {
                BookingStatus bookingStatus = BookingStatus.valueOf(status.toUpperCase());
                return bookingRepo.findByStatus(bookingStatus)
                                .stream()
                                .map(this::convertToDTO)
                                .collect(Collectors.toList());
        }

        @Override
        public BookingDTO getBookingById(Long id) {
                Booking booking = bookingRepo.findById(id)
                                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy đặt phòng"));
                return convertToDTO(booking);
        }

        @Override
        public DashboardStatsDTO getBookingStats() {
                LocalDate today = LocalDate.now();
                Long todayRentals = bookingRepo.countTodayActiveBookings(today);
                Long occupiedRooms = roomRepo.countByStatus(RoomStatus.OCCUPIED);
                Long reservedRooms = roomRepo.countByStatus(RoomStatus.RESERVED);
                Long cleaningRooms = roomRepo.countByStatus(RoomStatus.CLEANING);
                Long totalRooms = roomRepo.count();
                Long availableRooms = roomRepo.countByStatus(RoomStatus.AVAILABLE);

                return DashboardStatsDTO.builder()
                                .todayRentals(todayRentals)
                                .occupiedRooms(occupiedRooms)
                                .waitingRooms(reservedRooms)
                                .cleaningRooms(cleaningRooms)
                                .totalRooms(totalRooms)
                                .availableRooms(availableRooms)
                                .build();
        }

        private BookingDTO convertToDTO(Booking booking) {
                // Lấy promotion đầu tiên nếu có
                String promotionCode = null;
                String promotionName = null;

                if (booking.getBookingPromotions() != null && !booking.getBookingPromotions().isEmpty()) {
                        BookingPromotion firstPromotion = booking.getBookingPromotions().get(0);
                        promotionCode = firstPromotion.getPromotionCode();
                        promotionName = firstPromotion.getPromotionName();
                }

                return BookingDTO.builder()
                                .id(booking.getId())
                                .roomId(booking.getRoom().getId())
                                .roomNumber(booking.getRoom().getRoomNumber())
                                .customerName(booking.getCustomerName())
                                .phone(booking.getPhone())
                                .checkIn(booking.getCheckIn())
                                .checkOut(booking.getCheckOut())
                                .nights(booking.getNights())
                                .roomAmount(booking.getRoomAmount())
                                .serviceAmount(booking.getServiceAmount())
                                .discountAmount(booking.getDiscountAmount())
                                .promotionCode(promotionCode)
                                .promotionName(promotionName)
                                .totalAmount(booking.getTotalAmount())
                                .deposit(booking.getDeposit())
                                .status(booking.getStatus().name())
                                .notes(booking.getNotes())
                                .createdAt(booking.getCreatedAt())
                                .build();
        }

        @Override
        public BookingWithServicesDTO getBookingWithServices(Long id) {
                Booking booking = bookingRepo.findById(id)
                                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy đặt phòng"));

                List<BookingWithServicesDTO.BookingServiceItemDTO> serviceItems = booking.getBookingServices()
                                .stream()
                                .map(bs -> BookingWithServicesDTO.BookingServiceItemDTO.builder()
                                                .id(bs.getId())
                                                .serviceId(bs.getService().getId())
                                                .serviceName(bs.getService().getName())
                                                .quantity(bs.getQuantity())
                                                .price(bs.getUnitPrice())
                                                .totalPrice(bs.getTotalPrice())
                                                .build())
                                .collect(Collectors.toList());

                // ✨ Thêm danh sách promotions
                List<BookingWithServicesDTO.BookingPromotionItemDTO> promotionItems = booking.getBookingPromotions()
                                .stream()
                                .map(bp -> BookingWithServicesDTO.BookingPromotionItemDTO.builder()
                                                .id(bp.getId())
                                                .promotionId(bp.getPromotion().getId())
                                                .promotionCode(bp.getPromotionCode())
                                                .promotionName(bp.getPromotionName())
                                                .discountAmount(bp.getDiscountAmount())
                                                .appliedAt(bp.getAppliedAt())
                                                .build())
                                .collect(Collectors.toList());

                return BookingWithServicesDTO.builder()
                                .id(booking.getId())
                                .roomId(booking.getRoom().getId())
                                .roomNumber(booking.getRoom().getRoomNumber())
                                .customerName(booking.getCustomerName())
                                .phone(booking.getPhone())
                                .checkIn(booking.getCheckIn())
                                .checkOut(booking.getCheckOut())
                                .nights(booking.getNights())
                                .roomAmount(booking.getRoomAmount())
                                .serviceAmount(booking.getServiceAmount())
                                .discountAmount(booking.getDiscountAmount())
                                .totalAmount(booking.getTotalAmount())
                                .deposit(booking.getDeposit())
                                .status(booking.getStatus().name())
                                .notes(booking.getNotes())
                                .createdAt(booking.getCreatedAt())
                                .services(serviceItems)
                                .promotions(promotionItems)
                                .build();
        }

        @Override
        @Transactional
        public BookingDTO changeRoom(Long bookingId, Long newRoomId) {
                Booking booking = bookingRepo.findById(bookingId)
                                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy đặt phòng"));

                if (booking.getStatus() != BookingStatus.PENDING && booking.getStatus() != BookingStatus.CONFIRMED) {
                        throw new IllegalStateException(
                                        "Chỉ có thể đổi phòng khi booking đang ở trạng thái chờ xác nhận hoặc đã xác nhận");
                }

                Room oldRoom = booking.getRoom();
                Room newRoom = roomRepo.findById(newRoomId)
                                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy phòng mới"));

                if (newRoom.getStatus() != RoomStatus.AVAILABLE) {
                        throw new IllegalStateException("Phòng mới không khả dụng");
                }

                List<Booking> overlappingBookings = bookingRepo.findOverlappingBookings(
                                newRoomId,
                                booking.getCheckIn(),
                                booking.getCheckOut());

                boolean hasCheckedInBooking = overlappingBookings.stream()
                                .anyMatch(b -> b.getStatus() == BookingStatus.CHECKED_IN);

                if (hasCheckedInBooking) {
                        throw new IllegalStateException("Phòng mới đang có khách. Vui lòng chọn phòng khác.");
                }

                long nights = booking.getNights();
                BigDecimal newRoomAmount = newRoom.getPrice().multiply(BigDecimal.valueOf(nights));

                booking.setRoom(newRoom);
                booking.setRoomAmount(newRoomAmount);
                if (booking.getBookingPromotions() != null && !booking.getBookingPromotions().isEmpty()) {
                        BigDecimal newSubtotal = newRoomAmount.add(booking.getServiceAmount());
                        for (BookingPromotion bp : booking.getBookingPromotions()) {
                                try {
                                        BigDecimal newDiscount = promotionService.calculateDiscount(
                                                        bp.getPromotionCode(),
                                                        newSubtotal);
                                        bp.setDiscountAmount(newDiscount);
                                } catch (Exception e) {
                                        logService.log(LogType.WARNING, "Không thể tính lại giảm giá khi đổi phòng",
                                                        "Admin",
                                                        e.getMessage());
                                }
                        }
                }

                booking.recalculateTotalAmount();

                List<Booking> oldRoomActiveBookings = bookingRepo.findActiveBookingsByRoom(oldRoom.getId());
                boolean hasOtherActiveBooking = oldRoomActiveBookings.stream()
                                .anyMatch(b -> !b.getId().equals(bookingId) &&
                                                (b.getStatus() == BookingStatus.CHECKED_IN ||
                                                                b.getStatus() == BookingStatus.CONFIRMED ||
                                                                b.getStatus() == BookingStatus.PENDING));

                if (!hasOtherActiveBooking) {
                        oldRoom.setStatus(RoomStatus.AVAILABLE);
                        roomRepo.save(oldRoom);
                }

                if (booking.getStatus() == BookingStatus.CONFIRMED || booking.getStatus() == BookingStatus.PENDING) {
                        newRoom.setStatus(RoomStatus.RESERVED);
                        roomRepo.save(newRoom);
                }

                booking = bookingRepo.save(booking);

                logService.log(LogType.SUCCESS, "Đổi phòng", "Admin",
                                String.format("Đã đổi phòng từ %s sang %s cho khách %s",
                                                oldRoom.getRoomNumber(), newRoom.getRoomNumber(),
                                                booking.getCustomerName()),
                                String.format("Giá phòng mới: %s, Tổng tiền mới: %s",
                                                newRoomAmount, booking.getTotalAmount()));

                return convertToDTO(booking);
        }
}