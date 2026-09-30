package com.neuringo.neuringobe.child.security;

import java.io.Serializable;
import java.util.UUID;

public record ChildPrincipal(UUID childId) implements Serializable {}
