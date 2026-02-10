package auth.dto.request;

import auth.model.Theme;
import jakarta.validation.constraints.NotNull;

public record UserSettingsRequest(
        @NotNull
        Theme theme
) {}