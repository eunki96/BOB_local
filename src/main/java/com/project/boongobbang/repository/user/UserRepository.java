package com.project.boongobbang.repository.user;

import com.project.boongobbang.domain.entity.user.User;
import com.project.boongobbang.enums.Gender;
import com.project.boongobbang.enums.SeoulGu;
import com.project.boongobbang.enums.UserType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Repository
public interface UserRepository extends JpaRepository<User, String> {
    Optional<User> findByUserNaverId(String userNaverId);
    Boolean existsByUserNaverId(String userNaverId);
    Optional<User> findUserByUserEmail(String userEmail);
    Optional<User> findUserByUserNaverId(String userNaverId);
    void deleteUserByUserEmail(String userEmail);



    Page<User> findAll(Pageable pageable);


    @Query("SELECT u " +
            "FROM User u " +
            "WHERE u.userType " +
            "IN :userTypes " +
            "AND u.userLocation = :location " +
            "AND u.userGender = :gender " +
            "AND ABS(YEAR(u.userBirth) - YEAR(:birth)) <= 4" +
            "AND u.isPaired = false")
    List<User> findUsersByPriority1(@Param("userTypes") List<UserType> userTypes,
                                    @Param("location") SeoulGu location,
                                    @Param("gender") Gender gender,
                                    @Param("birth") LocalDate birth);

    @Query("SELECT u " +
            "FROM User u " +
            "WHERE u.userLocation = :location " +
            "AND u.userGender = :gender " +
            "AND u NOT IN :excludedUsers AND u.isPaired = false")
    List<User> findByUserLocationAndUserGenderExcludingUsers(
            @Param("location") SeoulGu location,
            @Param("gender") Gender gender,
            @Param("excludedUsers") Set<User> excludedUsers);

    @Query("SELECT u FROM User u WHERE u.userGender = :gender AND u NOT IN :excludedUsers AND u.isPaired = false")
    List<User> findByUserGenderExcludingUsers(
            @Param("gender") Gender gender,
            @Param("excludedUsers") Set<User> excludedUsers);

    @Query("SELECT u FROM User u WHERE u NOT IN :excludedUsers AND u.isPaired = false")
    List<User> findAllExcludingUsers(
            @Param("excludedUsers") Set<User> excludedUsers);

}
