package com.project.boongobbang.service;

import com.amazonaws.SdkClientException;
import com.amazonaws.services.s3.AmazonS3Client;
import com.amazonaws.services.s3.model.CannedAccessControlList;
import com.amazonaws.services.s3.model.PutObjectRequest;
import com.project.boongobbang.domain.dto.roommate.HistoryResponseDto;
import com.project.boongobbang.domain.dto.roommate.NotificationResponseDto;
import com.project.boongobbang.domain.dto.roommate.RoommateResponseDto;
import com.project.boongobbang.domain.dto.token.TokenResponseDto;
import com.project.boongobbang.domain.dto.user.*;
import com.project.boongobbang.domain.entity.roommate.Notification;
import com.project.boongobbang.domain.entity.roommate.Roommate;
import com.project.boongobbang.domain.entity.user.User;
import com.project.boongobbang.domain.entity.user.UserScore;
import com.project.boongobbang.enums.NotificationType;
import com.project.boongobbang.enums.UserType;
import com.project.boongobbang.exception.AppException;
import com.project.boongobbang.exception.ErrorCode;
import com.project.boongobbang.jwt.JwtUtils;
import com.project.boongobbang.repository.redis.RedisRefreshTokenRepository;
import com.project.boongobbang.repository.roommate.NotificationRepository;
import com.project.boongobbang.repository.roommate.RoommateRepository;
import com.project.boongobbang.repository.user.UserRepository;
import com.project.boongobbang.repository.user.UserScoreRepository;
import com.project.boongobbang.repository.user.UserTypeFavorRepository;
import com.project.boongobbang.util.UserTypeFavor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.multipart.MultipartFile;

import javax.persistence.OptimisticLockException;
import javax.transaction.Transactional;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URL;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.project.boongobbang.enums.CleanCount.*;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserService {
    private  String bucketName = "boong-o-bbang-img";
    private final AmazonS3Client amazonS3Client;

    private final JwtUtils jwtUtils;
    private final AuthenticationManager authenticationManager;
    private final RedisRefreshTokenRepository refreshTokenRepository;

    private final UserRepository userRepository;
    private final RoommateRepository roommateRepository;
    private final UserScoreRepository userScoreRepository;
    private final NotificationRepository notificationRepository;
    private final UserTypeFavorRepository userTypeFavorRepository;


    //DB에 유저 정보 있는지 체크
    public Boolean validate(UserValidateDto dto) {
        return userRepository.existsByUserNaverId(dto.getUserNaverId());
    }

    //로그인 유저 정보 추출
    public String getLoginUserInfo(){
        return jwtUtils.extractUsername(
                (String) RequestContextHolder
                .currentRequestAttributes()
                .getAttribute(AUTHORIZATION, RequestAttributes.SCOPE_REQUEST)
        );
    }

    //회원가입
    public void signUp(UserSignUpDto dto) {
        userRepository.findByUserNaverId(dto.getUserNaverId()).ifPresent(user -> {
            throw new AppException(ErrorCode.USER_ALREADY_EXISTS, "이미 존재하는 회원입니다.");
        });

        User user = dto.toEntity();

        user.setUserType(determineUserType(user));
        System.out.println(user.getUserPhotoUrl());
        userRepository.save(user);
    }

    //유저 생성/수정 시 UserType 설정
    public String determineUserType(User user) {

        List<Function<User, String>> userCharacteristics = Arrays.asList(
                u -> {
                    if (u.getUserCleanCount() == ONE_TO_TWO) {
                        return "CLEAN_1_2_";
                    } else if (u.getUserCleanCount() == THREE_TO_FOUR) {
                        return "CLEAN_3_4_";
                    } else if (u.getUserCleanCount() == MORE_THAN_FOUR) {
                        return "CLEAN_MORE_4_";
                    } else {
                        throw new IllegalArgumentException("일치하는 유저타입이 없습니다");
                    }
                },
                u -> {
                    String mbti = u.getUserMBTI().toString();
                    return mbti.charAt(0) + "_" + mbti.charAt(2) + "_";
                },
                u -> u.getUserIsSmoker() ? "SMOKER_" : "NON_SMOKER_",
                u -> u.getUserIsNocturnal() ? "NOCTURNAL" : "DIURNAL"
        );

        String userTypeStr = userCharacteristics.stream()
                .map(f -> f.apply(user))
                .collect(Collectors.joining());

        return userTypeStr;
    }

    //로그인
    public TokenResponseDto signIn(UserSignInDto dto) {
        String accessToken = (String) RequestContextHolder
                .currentRequestAttributes()
                .getAttribute(AUTHORIZATION, RequestAttributes.SCOPE_REQUEST);

        UserDetails user = (UserDetails) authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(dto.getUserNaverId(), "")
        ).getPrincipal();

        if (user == null) {
            throw new AppException(ErrorCode.USER_NOT_FOUND, "[Error] 유저 존재하지 않습니다");
        }

        //INVALID는 처리된 이후. 즉, 유효성 검증을 끝낸 유효한 access token이다.
        if (accessToken != null) {
            return new TokenResponseDto(accessToken);
        }
        refreshTokenRepository.saveRefreshToken(user.getUsername(), jwtUtils.createRefreshToken());

        return new TokenResponseDto(jwtUtils.generateToken(user));
    }

    //유저 정보 업데이트
    @Transactional
    public User updateUser(User user, UserUpdateRequestDto dto) {
        log.info("========== 유저 수정 시작 ==========\n");

        if (!Objects.equals(user.getUserNickname(), dto.getUserNickname())) {
            user.setUserNickname(dto.getUserNickname());
        }
        if (!Objects.equals(user.getUserCleanCount(), dto.getUserCleanCount())) {
            user.setUserCleanCount(dto.getUserCleanCount());
        }
        if (!Objects.equals(user.getUserLocation(), dto.getUserLocation())) {
            user.setUserLocation(dto.getUserLocation());
        }
        if (!Objects.equals(user.getUserMBTI(), dto.getUserMbti())) {
            user.setUserMBTI(dto.getUserMbti());
        }
        if (!Objects.equals(user.getUserHasPet(), dto.getUserHasPet())) {
            user.setUserHasPet(dto.getUserHasPet());
        }
        if (!Objects.equals(user.getUserHasExperience(), dto.getUserHasExperience())) {
            user.setUserHasExperience(dto.getUserHasExperience());
        }
        if (!Objects.equals(user.getUserIsSmoker(), dto.getUserIsSmoker())) {
            user.setUserIsSmoker(dto.getUserIsSmoker());
        }
        if (!Objects.equals(user.getUserIsNocturnal(), dto.getUserIsNocturnal())) {
            user.setUserIsNocturnal(dto.getUserIsNocturnal());
        }
        if (!Objects.equals(user.getUserIntroduction(), dto.getUserIntroduction())) {
            user.setUserIntroduction(dto.getUserIntroduction());
        }
        if (!Objects.equals(user.getUserPhotoUrl(), dto.getUserPhotoUrl())) {
            user.setUserPhotoUrl(dto.getUserPhotoUrl());
        }

        user.setUserType(determineUserType(user));

        try {
            userRepository.save(user);
        } catch (OptimisticLockException e) {
            // 재시도 로직
            retryUpdate(user);
        }
        return user;
    }
    private void retryUpdate(User user) {
        int retryCount = 3;
        while (retryCount > 0) {
            try {
                userRepository.save(user);
                return;
            } catch (OptimisticLockException e) {
                retryCount--;
            }
        }
        throw new RuntimeException("데이터 업데이트에 실패했습니다.");
    }

    //유저 삭제
    @Transactional
    public UserProfileDto deleteUser(String userEmail) {
        log.info("========== 유저 탈퇴 시작 ==========\n");
        User user = findUserByUserEmail(userEmail);

        notificationRepository.deleteNotificationsByUserEmail(userEmail);
        roommateRepository.deleteRoommatesByUserEmail(userEmail);
        userScoreRepository.deleteUserScoresByUserEmail(userEmail);

        UserProfileDto dto = new UserProfileDto(user);
        userRepository.deleteUserByUserEmail(userEmail);
        return dto;
    }


    //식별자로 User 검색
    public User findUserByUserEmail(String userEmail) {
        log.info("========== 이메일 기반 유저 검색 ==========\n");
        User user = userRepository.findUserByUserEmail(userEmail)
                .orElseThrow(
                        () -> new RuntimeException("[Error] 존재하지 않는 유저입니다")
                );
        return user;
    }

    //네이버 ID 로 User 검색
    public User findUserByUserNaverId(String userNaverId){
        log.info("========== 네이버 아이디 기반 유저 검색 시작 ==========\n");
        User user = userRepository.findByUserNaverId(userNaverId)
                .orElseThrow(
                        () -> new RuntimeException("[Error] 존재하지 않는 유저입니다")
                );
        return user;
    }

    //식별자로 Notification 검색
    public Notification findNotificationByNotificationId(Long notificationtId) {
        log.info("========== 알림 식별자 기반 알림 검색 ==========\n");
        Notification notification;
        try {
            notification = notificationRepository.findNotificationByNotificationId(notificationtId);
        } catch (RuntimeException e) {
            throw new RuntimeException("[Error] 존재하지 않는 알림입니다.");
        }
        return notification;
    }

    //식별자로 UserScore 검색
    public UserScore findUserScoreByUserScoreId(Long userScoreId) {
        log.info("========== 유저 점수 식별자 기반 유저 점수 검색 ==========\n");
        UserScore userScore;
        try {
            userScore = userScoreRepository.findUserScoreByUserScoreId(userScoreId);
        } catch (RuntimeException e) {
            throw new RuntimeException("[Error] 존재하지 않는 알림입니다.");
        }
        return userScore;
    }

    //식별자로 Roommate 검색
    public Roommate findRoommateByRoommateId(Long roommateId) {
        log.info("========== 룸메이트 식별자 기반 룸메이트 검색 시작 ==========\n");
        Roommate roommate;
        try {
            roommate = roommateRepository.findRoommateByRoommateId(roommateId);
        } catch (RuntimeException e) {
            throw new RuntimeException("[Error] 존재하지 않는 룸메이트 관계입니다.");
        }
        return roommate;
    }

    //구성 User 의 Email 로 Roommate 검색
    public Roommate findRoommateByUsers(String userEmail1, String userEmail2) {
        Roommate roommate;
        try {
            roommate = roommateRepository.findRoommateByUsers(userEmail1, userEmail2);
        } catch (RuntimeException e) {
            return null;
        }
        return roommate;
    }

    //로그인 User 의 현재 룸메이트 검색
    public Roommate findRoommateByLoginUser(String userEmail) {
        log.info("========== 로그인 유저 포함된 룸메이트 관계 검색 ==========\n");
        Roommate roommate;
        try {
            roommate = roommateRepository.findRoommateByLoginUser(userEmail)
                    .orElseThrow(
                            () -> new RuntimeException()
                    );
        } catch (RuntimeException e) {
            return null;
        }
        return roommate;
    }

    //전체 User 페이지로 검색
    public List<UserSimpleDto> getUsersByPage(int pageNumber) {
        log.info("========== 전체 유저 페이지로 검색 ==========\n");
        Pageable pageable = PageRequest.of(pageNumber, 10);
        Page<User> userPage = userRepository.findAll(pageable);
        return userPage.stream()
                .map(user -> new UserSimpleDto(user))
                .collect(Collectors.toList());
    }

    //전체 룸메이트 검색
    public List<RoommateResponseDto> getRoommateList(int pageNumber){
        log.info("========== 전체 룸메이트 관계 페이지로 검색 ==========\n");
        Pageable pageable = PageRequest.of(pageNumber, 10);
        Page<Roommate> roommatePage = roommateRepository.findAll(pageable);
        return roommatePage.stream()
                .map(roommate -> new RoommateResponseDto(roommate))
                .collect(Collectors.toList());

    }

    @Transactional
    //유저의 현재 룸메이트 매칭상태 조회(메인 페이지)
    public List<UserProfileDto> getUserAndRoommate(User user){
        log.info("========== 로그인 유저와 상대 룸메이트 검색 ==========\n");
        List<UserProfileDto> userProfileDtoList = new ArrayList<>();
        userProfileDtoList.add(new UserProfileDto(user));
        if(user.isPaired()){
            log.info("======= [isPaired == true] =======");
            User roommateUser = roommateRepository.findCurrentRoommateByUserEmail(user.getUserEmail())
                    .orElseThrow(() -> new RuntimeException("getUserAndRoommate 에러"));
            log.info("======= [userProfileDtoList.add(new UserProfileDto(roommateUser));] =======");
            userProfileDtoList.add(new UserProfileDto(roommateUser));
        }
        return userProfileDtoList;
    }

    //User 입력받아 해당 유저의 Notification 목록 페이지로 검색
    public List<NotificationResponseDto> getNotificationsByUserAndPage(User user, int pageNumber){
        log.info("========== User 입력받아 해당 유저의 Notification 목록 페이지로 검색 ==========\n");
        Pageable pageable = PageRequest.of(pageNumber, 10);
        Page<Notification> notificationPage = notificationRepository.findNotificationsByCheckUser(user, pageable);
        return notificationPage.stream()
                .map(notification -> new NotificationResponseDto(notification))
                .collect((Collectors.toList()));
    }


    //User 입력받아 해당 유저의 이전 룸메이트 목록 페이지로 검색
    public List<HistoryResponseDto> returnUserPreviousRoommatesByPage(User user, int pageNumber) {
        log.info("========== User 입력받아 해당 유저의 이전 룸메이트 목록 페이지로 검색 ==========\n");
        Pageable pageable = PageRequest.of(pageNumber, 10);
        Page<UserScore> userScorepage = userScoreRepository.findUserScoresByRatingUserOrderByCreatedAt(user, pageable);
        return userScoreRepository.findUserScoresByRatingUserOrderByCreatedAt(user).stream()
                .map(userScore -> new HistoryResponseDto(userScore, userScore.getRatedUser()))
                .collect(Collectors.toList());
    }

    //UserScore 가 이미 평가했는지 여부 반환
    public boolean validateUserScoreRated(Long userScoreId){
        log.info("========== UserScore 가 이미 평가했는지 여부 반환 ==========\n");
        UserScore userScore = findUserScoreByUserScoreId(userScoreId);
        return userScore.isRated();
    }





    /*** DTO 반환 ***/

    //User(본인) 입력받아 UserProfileDto 반환
    //무조건 모든 정보를 공개
    public UserProfileDto returnMyProfileDto(User user) {
        log.info("========== 본인 UserProfileDto ==========\n");
        UserProfileDto dto = new UserProfileDto(user);
        return dto;
    }

    //User 입력받아 UserProfileDto 반환
    public UserProfileDto returnUserProfileDto(User me, User user) {
        //룸메이트라면 userMobile 까지 공개
        log.info("========== 다른 유저의 UserProfileDto ==========\n");
        boolean isRoommate = (roommateRepository.findRoommateByUsers(me.getUserEmail(), user.getUserEmail()) == null) ? false : true;
        UserProfileDto dto = new UserProfileDto(user, isRoommate);
        return dto;
    }

    //User 입력받아 UserSimpleDto 반환
    public UserSimpleDto returnUserSimpleDto(User user) {
        UserSimpleDto dto = new UserSimpleDto(user);
        return dto;
    }

    //User 입력받아 UserResponseDto 반환
    public UserResponseDto returnUserDto(User user) {
        log.info("========== User 입력받아 UserResponseDto 반환 ==========\n");
        UserResponseDto dto = new UserResponseDto(user);
        return dto;
    }








    /*** 사진 관련 ***/

    //사진 저장
    @org.springframework.transaction.annotation.Transactional
    public String savePhoto(MultipartFile file, User user) {

        log.info("[UserService] savePhoto");
        log.info("user.getUserPhotoUrl = {}", user.getUserPhotoUrl());
        if(!(user.getUserPhotoUrl().equals("empty"))){
            log.info("[UserService] updateUserProfilePhoto : if(!(user.getUserPhotoUrl() == \"empty\"))");
            deleteUserProfilePhoto(user);
        }

        String filename = file.getOriginalFilename();
        String storedFileName = getUserProfileFileName(filename);

        String photoUrl = uploadFileToS3(storedFileName, file);
        user.setUserPhotoUrl(photoUrl);

        return photoUrl;
    }

    //유저 프로필사진 삭제
    @Transactional
    public User deleteUserProfilePhoto(User user) {
        log.info("[UserService] deleteUserProfilePhoto");

        String fileName = extractProfilePhotoFileName(user);
        log.info("[UserService] deleteUserProfilePhoto : fileName = {}", fileName);

        user.setUserPhotoUrl("empty");
        deleteFileFromS3(fileName);

        return userRepository.save(user);
    }

    //유저 프로필 사진 이름 생성
    public String getUserProfileFileName(String fileName){
        return  "user_profile/"  + UUID.randomUUID()+ fileName.substring(fileName.lastIndexOf('.'));
    }

    //유저의 프로필 사진 이름 추출
    public static String extractProfilePhotoFileName(User user) {
        log.info("[UserService] extractProfilePhotoFileName");
        String path = null;

        try {
            log.info("[???] = {}", user.getUserPhotoUrl());
            URL url = new URL(user.getUserPhotoUrl());
            log.info("url = {}", url);
            path = url.getPath();
            log.info("path = {}", path);

            if (path.startsWith("/")) {
                path = path.substring(1);
                log.info("path = {}", path);
            }
        } catch (Exception e) {
            throw new RuntimeException("RuntimeException 발생");
        }
        log.info("path = {}", path);
        return path;
    }

    //S3에 사진 업로드
    public String uploadFileToS3(String fileName, MultipartFile file) {

        try {
            File convertedFile = convertFile(file);
            String uploadingFileName = fileName;

            amazonS3Client.putObject(new PutObjectRequest(bucketName, uploadingFileName, convertedFile)
                    .withCannedAcl(CannedAccessControlList.PublicRead));
            convertedFile.delete();
            String url = amazonS3Client.getUrl(bucketName, uploadingFileName).toString();
            return url;
        } catch (IOException e) {
            throw new RuntimeException();
        }
    }

    //S3 에서 사진 삭제
    public void deleteFileFromS3(String fileName) {
        log.info("[UserService] deleteFileFromS3");
        try {
            amazonS3Client.deleteObject(bucketName, fileName);
        } catch (SdkClientException e) {
            throw new RuntimeException();
        }
    }

    //MultipartFile을 File로 전환
    private File convertFile(MultipartFile file) throws IOException {
        File convertingFile = new File(Objects.requireNonNull(file.getOriginalFilename()));
        FileOutputStream fileOutputStream = new FileOutputStream(convertingFile);
        fileOutputStream.write(file.getBytes());
        fileOutputStream.close();

        return convertingFile;
    }

    //사진 저장 이름 생성
    public String getStoredFileName(String fileName){
        return UUID.randomUUID() + fileName.substring(fileName.lastIndexOf('.'));
    }










    /*** 룸메이트 관련 ***/

    //룸메이트 요청 전송
    public Notification sendRoommateRequest(String senderUserEmail, String receiverUserEmail) {
        log.info("========== 룸메이트 요청 전송 ==========\n");

        User senderUser = findUserByUserEmail(senderUserEmail);
        User receiverUser = findUserByUserEmail(receiverUserEmail);

        //receiverUser 에게 요청 수신 알림 생성
        StringBuilder requestMessage = new StringBuilder()
                .append(senderUser.getUsername())
                .append("님으로부터 룸메이트 신청을 받았습니다.");
        Notification notification = Notification.builder()
                .checkUser(receiverUser)
                .relatedUser(senderUser)
                .notificationType(NotificationType.REQUEST)
                .message(requestMessage.toString())
                .build();
        return notificationRepository.save(notification);
    }
    //존재하는 알림인지 확인
    public boolean validateIsExistingNotification(String senderUserEmail, String receiverUserEmail){
        log.info("========== 존재하는 알림인지 확인 ==========\n");
        User senderUser = findUserByUserEmail(senderUserEmail);
        User receiverUser = findUserByUserEmail(receiverUserEmail);

        return notificationRepository.existsByCheckUserAndRelatedUser(receiverUser, senderUser, NotificationType.REQUEST);
    }
    //요청 송/수신자가 이미 룸메이트가 있는지 확인
    public boolean validateIsPaired(String senderUserEmail, String receiverEmail){
        log.info("========== 이미 송/수신자의 룸메이트가 있는지 확인 ==========\n");
        return (findUserByUserEmail(senderUserEmail).isPaired() || findUserByUserEmail(receiverEmail).isPaired());
    }
    public boolean validateIsPaired2(Notification notification){
        return notification.getRelatedUser().isPaired();
    }

    //룸메이트 요청 수락
    @Transactional
    public Roommate acceptRoommateRequest(Long notificationId) {
        log.info("========== 룸메이트 요청 수락 ==========\n");
        Notification notification = findNotificationByNotificationId(notificationId);

        User user1 = notification.getCheckUser();
        user1.setIsPaired(true);
        User user2 = notification.getRelatedUser();
        user2.setIsPaired(true);
        userRepository.save(user1);
        userRepository.save(user2);

        //해당 알림 삭제
        log.info("해당 알림 삭제");
        deleteNotification(notificationId);

        //룸메이트 관계 생성
        log.info("룸메이트 관계 생성");
        Roommate roommate = Roommate.builder()
                .user1(user1)
                .user2(user2)
                .build();
        roommate.start();
        Roommate newRoommate = roommateRepository.save(roommate);

        //user1 과 user2에게 룸메이트 성사 알림 생성
        log.info("user1 과 user2에게 룸메이트 성사 알림 생성");
        String baseNotificationMessage = " 님과 룸메이트가 되었습니다.";
        Notification baseNotification = Notification.builder()
                .notificationType(NotificationType.ROOMMATE)
                .build();

        log.info("notification1");
        Notification notification1 = baseNotification.toBuilder()
                .checkUser(user1)
                .relatedUser(user2)
                .message(user2.getUsername() + baseNotificationMessage)
                .build();
        notificationRepository.save(notification1);
        log.info("notification2");
        Notification notification2 = baseNotification.toBuilder()
                .checkUser(user2)
                .relatedUser(user1)
                .message(user1.getUsername() + baseNotificationMessage)
                .build();
        notificationRepository.save(notification2);


        /* 룸메이트가 생성되면
        1/ UserTypeFavor 의 userTypeCode1 과 userTypeCode2 의 userType 을 갖고있는 UserTypeFavor 을 검색
            => userTypeFavor

        2/ userTypeFavor 가 있으면 count++
            userTypeFavor 가 없으면 count = 1 로 생성
         */

        UserType userTypeCode1 = user1.getUserType();
        UserType userTypeCode2 = user2.getUserType();

        // 이미 해당 UserType 조합으로 생성된 UserTypeFavor가 있는지 검색
        Optional<UserTypeFavor> optionalUserTypeFavor = userTypeFavorRepository.findByUserTypeCodes(userTypeCode1, userTypeCode2);

        UserTypeFavor userTypeFavor;
        if(optionalUserTypeFavor.isPresent()) {
            // UserTypeFavor 가 이미 존재하면 count 증가
            userTypeFavor = optionalUserTypeFavor.get();
            userTypeFavor.setCount(userTypeFavor.getCount() + 1);
        } else {
            // UserTypeFavor 가 없다면 새로 생성
            userTypeFavor = UserTypeFavor.builder()
                    .userType1(userTypeCode1)
                    .userType2(userTypeCode2)
                    .count(1)
                    .build();
        }

        userTypeFavorRepository.save(userTypeFavor);

        return newRoommate;
    }

    //룸메이트 신청 거절, 알림 삭제
    @Transactional
    public void deleteNotification(Long notificationId) {
        log.info("========== 룸메이트 신청 거절 / 알림 삭제 ==========\n");
        notificationRepository.deleteNotificationByNotificationId(notificationId);
    }

    //룸메이트 관계 종료
    @Transactional
    public void endRoommate(Long roommateId){
        log.info("========== 룸메이트 관계 종료 ==========\n");
        Roommate roommate = findRoommateByRoommateId(roommateId);
        roommate.end();
        roommateRepository.save(roommate);

        User user1 = roommate.getUser1();
        user1.setIsPaired(false);
        User user2 = roommate.getUser2();
        user2.setIsPaired(false);
        userRepository.save(user1);
        userRepository.save(user2);

        //관계 종료와 동시에 서로에 대한 UserScore 생성 (평가 X)
        UserScore userScore1 = UserScore.builder()
                .ratingUser(roommate.getUser1())
                .ratedUser(roommate.getUser2())
                .score(0)
                .isRated(false)
                .build();
        userScoreRepository.save(userScore1);
        UserScore userScore2 = UserScore.builder()
                .ratingUser(roommate.getUser2())
                .ratedUser(roommate.getUser1())
                .score(0)
                .isRated(false)
                .build();
        userScoreRepository.save(userScore2);
    }

    //룸메이트 평가
    @Transactional
    public void rateRoommate(Long userScoreId, int score) {
        log.info("========== 룸메이트 평가 ==========\n");
        UserScore userScore = findUserScoreByUserScoreId(userScoreId);
        User roommate = userScore.getRatedUser();

        userScore.setScore(score);
        userScore.setIsRated(true);
        userScoreRepository.save(userScore);

        //평가 당한 유저의 평균 점수 업데이트
        updateAverageScore(roommate);
    }

    // 평균 점수 업데이트
    private void updateAverageScore(User user) {
        log.info("========== User 평균 점수 업데이트 ==========\n");

        //userId 갖는 모든 UserScore 의 score 합산
        BigDecimal totalScore = userScoreRepository.sumScoresByRatedUserEmail(user.getUserEmail());

        //UserAverageScore 의 현재 count + 1
        Long count = user.getRatedCount() + 1;

        //새로운 평균 점수
        BigDecimal averageScore = totalScore.divide(BigDecimal.valueOf(count), 2, RoundingMode.HALF_UP);

        user.setAverageScore(averageScore);
        user.setRatedCount(count);

        userRepository.save(user);
    }




    /*** 추천 알고리즘 관련 ***/

    //룸메이트 유저 추천
    public List<UserProfileDto> recommendRoommates(User user1) {
        log.info("========== 추천 시작 ==========\n");
        long startTime = System.currentTimeMillis(); // 시작 시간 측정

        Set<UserProfileDto> recommendedDtos = new LinkedHashSet<>();

        // 1st priority
        List<UserType> favoredUserTypes = userTypeFavorRepository.findFavoredUserTypesByUserType(user1.getUserType());
        log.info("Favored User Types: {}", favoredUserTypes);
        recommendedDtos.addAll(userRepository.findUserProfileDtosByPriority1(favoredUserTypes, user1.getUserLocation(), user1.getUserGender(), user1.getUserBirth()));

        // For excluding users
        List<String> excludedEmails = recommendedDtos.stream()
                .map(UserProfileDto::getUserEmail)
                .collect(Collectors.toList());
        excludedEmails.add(user1.getUserEmail()); // exclude the requesting user itself

        // 2nd priority
        if (recommendedDtos.size() < 50) {
            List<UserProfileDto> secondPriorityDtos = userRepository.findUserProfileDtosByPriority2(user1.getUserLocation(), user1.getUserGender(), excludedEmails);
            recommendedDtos.addAll(secondPriorityDtos);
            excludedEmails.addAll(secondPriorityDtos.stream()
                    .map(UserProfileDto::getUserEmail)
                    .collect(Collectors.toList())); // Add 2nd priority users to the excluded list
        }

        // 3rd priority
        if (recommendedDtos.size() < 50) {
            List<UserProfileDto> thirdPriorityDtos = userRepository.findUserProfileDtosByPriority3(user1.getUserGender(), excludedEmails);
            recommendedDtos.addAll(thirdPriorityDtos);
            excludedEmails.addAll(thirdPriorityDtos.stream()
                    .map(UserProfileDto::getUserEmail)
                    .collect(Collectors.toList())); // Add 3rd priority users to the excluded list
        }

        // 4th priority
        if (recommendedDtos.size() < 50) {
            recommendedDtos.addAll(userRepository.findUserProfileDtosByPriority4(excludedEmails));
        }

        long endTime = System.currentTimeMillis(); // 종료 시간 측정
        long duration = endTime - startTime; // 시간 차이 계산

        log.info("Recommendation processing time: {} ms", duration); // 측정된 시간을 로그로 출력

        return new ArrayList<>(recommendedDtos);
    }












    //ReIssue

    public TokenResponseDto reissue(ReIssueDto dto) {
        String findRefreshToken = refreshTokenRepository.findRefreshToken(dto.getUserNaverId());

        if (findRefreshToken == null) {
            throw new AppException(ErrorCode.USER_ID_NOT_FOUND, "[Error] 재발행이 불가합니다.");
        }

        if (jwtUtils.isTokenValid(findRefreshToken)) {
            UserDetails user = (UserDetails) authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(dto.getUserNaverId(), "")
            ).getPrincipal();

            return new TokenResponseDto(jwtUtils.generateToken(user));
        }else {
            throw new AppException(ErrorCode.EXPIRED_TOKEN, "[Error] 재로그인 하십시오.");
        }

    }

    //전체 Notification 페이지로 검색
    public List<NotificationResponseDto> getNotificationsByPage(int pageNumber){
        log.info("========== 전체 Notification 페이지로 검색 ==========\n");
        Pageable pageable = PageRequest.of(pageNumber, 10);
        Page<Notification> notificationPage = notificationRepository.findAll(pageable);
        return notificationPage.stream()
                .map(notification -> new NotificationResponseDto(notification))
                .collect(Collectors.toList());
    }

}
