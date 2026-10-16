package com.ecommerce.web;

import com.ecommerce.dto.Dtos;
import com.ecommerce.model.User;
import com.ecommerce.repository.UserRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/users")
public class UserController {

    private final UserRepository users;

    public UserController(UserRepository users) {
        this.users = users;
    }

    @GetMapping
    public List<Dtos.UserDto> list() {
        return users.findAll(org.springframework.data.domain.Sort.by("id").ascending())
                .stream().map(u -> new Dtos.UserDto(u.getId(), u.getName(), u.getEmail()))
                .toList();
    }
}
