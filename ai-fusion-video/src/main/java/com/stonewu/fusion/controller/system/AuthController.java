package com.stonewu.fusion.controller.system;

import com.stonewu.fusion.common.CommonResult;
import com.stonewu.fusion.common.BusinessException;
import com.stonewu.fusion.controller.system.vo.LoginRespVO;
import com.stonewu.fusion.controller.system.vo.UserRespVO;
import com.stonewu.fusion.entity.system.Role;
import com.stonewu.fusion.entity.system.User;
import com.stonewu.fusion.security.SecurityUserDetails;
import com.stonewu.fusion.security.TokenService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import com.stonewu.fusion.controller.system.vo.LoginReqVO;
import com.stonewu.fusion.controller.system.vo.RegisterReqVO;
import com.stonewu.fusion.controller.system.vo.ProfileUpdateReqVO;
import com.stonewu.fusion.controller.system.vo.ChangePasswordReqVO;
import com.stonewu.fusion.service.system.UserService;

import java.util.List;

import static com.stonewu.fusion.common.CommonResult.success;

/**
 * 认证控制器
 */
@Tag(name = "认证管理")
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthenticationManager authenticationManager;
    private final TokenService tokenService;
    private final UserService userService;

    @PostMapping("/login")
    @Operation(summary = "登录")
    public CommonResult<LoginRespVO> login(@Valid @RequestBody LoginReqVO reqVO) {
        Authentication authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(reqVO.getUsername(), reqVO.getPassword())
        );
        SecurityUserDetails userDetails = (SecurityUserDetails) authentication.getPrincipal();
        TokenService.TokenPair tokenPair = tokenService.createToken(userDetails.getUserId(), userDetails.getUsername());

        User user = userService.getById(userDetails.getUserId());
        return success(buildLoginResp(tokenPair, user));
    }

    @PostMapping("/register")
    @Operation(summary = "注册")
    public CommonResult<LoginRespVO> register(@Valid @RequestBody RegisterReqVO reqVO) {
        if (!reqVO.getPassword().equals(reqVO.getConfirmPassword())) {
            throw new BusinessException(400, "两次输入的密码不一致");
        }
        User user = userService.register(reqVO.getUsername(), reqVO.getPassword(), reqVO.getNickname());
        TokenService.TokenPair tokenPair = tokenService.createToken(user.getId(), user.getUsername());
        return success(buildLoginResp(tokenPair, user));
    }

    @PostMapping("/refresh")
    @Operation(summary = "刷新令牌")
    public CommonResult<LoginRespVO> refresh(@Valid @RequestBody RefreshTokenReqVO reqVO) {
        TokenService.TokenPair tokenPair = tokenService.refreshAccessToken(reqVO.getRefreshToken());
        if (tokenPair == null) {
            return CommonResult.error(401, "刷新令牌无效或已过期");
        }

        // 获取用户信息
        Long userId = tokenService.getUserIdFromToken(tokenPair.getAccessToken());
        User user = userService.getById(userId);
        return success(buildLoginResp(tokenPair, user));
    }

    @PostMapping("/logout")
    @Operation(summary = "登出")
    public CommonResult<Boolean> logout(HttpServletRequest request) {
        String token = getTokenFromRequest(request);
        if (StringUtils.hasText(token)) {
            tokenService.removeToken(token);
        }
        return success(true);
    }

    @GetMapping("/user-info")
    @Operation(summary = "获取当前用户信息")
    public CommonResult<UserRespVO> getUserInfo() {
        SecurityUserDetails userDetails = (SecurityUserDetails) SecurityContextHolder.getContext()
                .getAuthentication().getPrincipal();
        User user = userService.getById(userDetails.getUserId());
        List<Role> roles = userService.getUserRoles(user.getId());

        UserRespVO respVO = new UserRespVO();
        respVO.setId(user.getId());
        respVO.setUsername(user.getUsername());
        respVO.setNickname(user.getNickname());
        respVO.setAvatar(user.getAvatar());
        respVO.setEmail(user.getEmail());
        respVO.setPhone(user.getPhone());
        respVO.setStatus(user.getStatus());
        respVO.setCreateTime(user.getCreateTime());
        respVO.setRoles(roles.stream().map(Role::getCode).toList());
        return success(respVO);
    }

    @PutMapping("/profile")
    @Operation(summary = "更新当前用户的个人资料")
    public CommonResult<Boolean> updateProfile(@Valid @RequestBody ProfileUpdateReqVO reqVO) {
        SecurityUserDetails userDetails = (SecurityUserDetails) SecurityContextHolder.getContext()
                .getAuthentication().getPrincipal();
        userService.updateProfile(userDetails.getUserId(), reqVO.getNickname(), reqVO.getEmail(), reqVO.getPhone());
        return success(true);
    }

    @PutMapping("/change-password")
    @Operation(summary = "修改当前用户密码")
    public CommonResult<Boolean> changePassword(@Valid @RequestBody ChangePasswordReqVO reqVO) {
        SecurityUserDetails userDetails = (SecurityUserDetails) SecurityContextHolder.getContext()
                .getAuthentication().getPrincipal();
        userService.changePassword(userDetails.getUserId(), reqVO.getOldPassword(), reqVO.getNewPassword());
        return success(true);
    }

    /**
     * 构建登录响应
     */
    private LoginRespVO buildLoginResp(TokenService.TokenPair tokenPair, User user) {
        return LoginRespVO.builder()
                .accessToken(tokenPair.getAccessToken())
                .refreshToken(tokenPair.getRefreshToken())
                .expiresIn(tokenPair.getExpiresIn())
                .userId(user.getId())
                .username(user.getUsername())
                .nickname(user.getNickname())
                .build();
    }

    private String getTokenFromRequest(HttpServletRequest request) {
        String bearerToken = request.getHeader("Authorization");
        if (StringUtils.hasText(bearerToken)) {
            if (bearerToken.startsWith("Bearer ")) {
                return bearerToken.substring(7);
            }
            return bearerToken;
        }
        return request.getParameter("access_token");
    }

    /**
     * 刷新令牌请求
     */
    @Data
    public static class RefreshTokenReqVO {
        @NotBlank(message = "刷新令牌不能为空")
        private String refreshToken;
    }
}
