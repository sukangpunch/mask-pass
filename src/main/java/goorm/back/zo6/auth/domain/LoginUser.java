package goorm.back.zo6.auth.domain;

import goorm.back.zo6.user.domain.Role;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;

public record LoginUser(Long id, String email, String role) implements UserDetails {

    public Long getId() {
        return id;
    }

    @Override
    public String getUsername() {
        return email;
    }

    public Role getRole() {
        return Role.of(role);
    }

    @Override
    public String getPassword() {
        return null;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return null;
    }
}
