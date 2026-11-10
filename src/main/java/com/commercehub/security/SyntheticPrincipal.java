package com.commercehub.security;

import java.util.UUID;

public record SyntheticPrincipal(UUID id, SyntheticRole role) {}
