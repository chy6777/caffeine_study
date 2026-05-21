package com.example.caffeine.repository;

import com.example.caffeine.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface UserRepository extends JpaRepository<User, Long> {

    List<User> findByNameContaining(String name);

    List<User> findByHotTrue();
}