# 🏨 Hotel Management System

> Hệ thống quản lý khách sạn toàn diện với React Native (Frontend) và Java Spring Boot (Backend)

## 📖 Giới thiệu

Hệ thống quản lý khách sạn hiện đại được xây dựng bằng React Native (Mobile App) và Spring Boot (Backend API). Ứng dụng cung cấp giải pháp toàn diện cho việc quản lý đặt phòng, khách hàng, dịch vụ, thanh toán và báo cáo thống kê.

## ✨ Tính năng chính

### 👤 Quản lý người dùng
- Đăng ký / Đăng nhập với JWT Authentication
- Phân quyền người dùng
- Quản lý hồ sơ cá nhân
- Đổi mật khẩu và quên mật khẩu

### 🏠 Quản lý phòng
- Quản lý danh sách phòng
- Phân loại phòng (Standard, Deluxe, Suite...)
- Theo dõi trạng thái phòng

### 📅 Quản lý đặt phòng
- Đặt phòng, Check-in/Check-out
- Tính toán giá tự động
- Áp dụng mã khuyến mãi
- Lịch sử đặt phòng

### 💳 Thanh toán
- Tích hợp PayOS (Cổng thanh toán Việt Nam)
- Thanh toán qua QR Code
- Theo dõi lịch sử giao dịch

### 🤖 AI Chatbot
- Trợ lý AI thông minh (Powered by Gemini 2.5 Flash)
- Trả lời tự động về phòng trống, giá cả
- Hỗ trợ khách hàng 24/7

### 📊 Báo cáo & Thống kê
- Dashboard tổng quan
- Doanh thu theo ngày/tháng/năm
- Biểu đồ trực quan

## 🛠 Công nghệ sử dụng

### Frontend
- React Native - Framework mobile đa nền tảng
- TypeScript
- Expo
- React Navigation

### Backend
- Spring Boot - Java framework
- Java JDK 21
- Spring Security & JWT
- PostgreSQL
- Spring Data JPA
- Hibernate - JPA implementation
- Spring Web MVC - RESTful API
- Spring HATEOAS - Hypermedia APIs
- Lombok - Code generation 
- Spring Validation - Dữ liệu validation

### Services
- PayOS - Payment gateway
- Gemini AI - Chatbot
- Railway - Cloud hosting

## 📁 Cấu trúc dự án
### Backend Structure (Spring Boot)
```
hotelmanagement/
└── src/main/java/com/nguyenhuuquang/hotelmanagement/
    ├── entity/                 # JPA Entities & Enums
    │   └── enums/             # RoomStatus, TransactionType, UserStatus
    ├── repository/            # Data Access Layer
    ├── service/               # Business Logic
    │   └── impl/             # Service Implementations
    ├── controller/            # REST API Controllers
    ├── config/                # Configuration Classes
    ├── dto/                   # Data Transfer Objects
    └── exception/             # Custom Exceptions
```

### Frontend Structure (React Native)
```
myapp/
├── app/                       # Screens
│   ├── (auth)/               # Authentication Screens
│   └── (drawer)/             # Main App Screens
├── components/                # Reusable Components
├── constants/                 # App Constants
├── contexts/                  # State Management (Context API)
├── hooks/                     # Custom React Hooks
├── services/                  # API Service Layer
└── styles/                    # Stylesheets
```
## 🚀 Cài đặt và chạy


### 1. Clone Repository
```bash
git clone https://github.com/nguyenhuuquang150805-debug/hotel-manager-backend.git
cd hotel-management
```

### 2. Cấu hình Backend

Tạo file `application.properties`:

```properties
server.port=8080
spring.application.name=hotelmanagement

# Database
spring.datasource.url=jdbc:postgresql://localhost:5432/hotel_db
spring.datasource.username=your_username
spring.datasource.password=your_password

# JPA
spring.jpa.hibernate.ddl-auto=update
spring.jpa.show-sql=true

# API Keys
payos.clientId=${PAYOS_CLIENT_ID}
payos.apiKey=${PAYOS_API_KEY}
payos.checksumKey=${PAYOS_CHECKSUM_KEY}
resend.api.key=${RESEND_API_KEY}
gemini.api.key=${GEMINI_API_KEY}
```

### 3. Chạy Backend
Backend đã deploy trên railway
```

### 4. Cấu hình Frontend

File `services/api.ts`:
```typescript
const RAILWAY_DOMAIN = 'hotel-manager-backend-production-81e4.up.railway.app';
const API_BASE_URL = `https://${RAILWAY_DOMAIN}/api`;
```

### 5. Chạy Frontend
```bash
cd myapp
npm install
npx expo start

# Chọn:
# - 'a' cho Android emulator
# - 'i' cho iOS simulator
# - Quét QR code bằng Expo Go
```

## 📚 API chính

### Authentication
```http
POST /api/auth/register
POST /api/auth/login
```

### Rooms
```http
GET    /api/rooms
POST   /api/rooms
PUT    /api/rooms/{id}
DELETE /api/rooms/{id}
```

### Bookings
```http
GET  /api/bookings
POST /api/bookings
GET  /api/bookings/{id}
```

### Chatbot
```http
POST /api/chatbot/message
GET  /api/chatbot/history/{userId}
```

### Payment
```http
POST /api/payment/create
```

## 👨‍💻 Tác giả

**Nguyễn Hữu Quang**
- GitHub: [@nguyenhuuquang](https://github.com/nguyenhuuquang150805-debug/hotel-manager-backend.git)

## 📄 License

MIT License
