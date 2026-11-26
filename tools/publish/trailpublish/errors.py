class PublishError(Exception):
    """A problem the publisher can explain and stop on."""


class ConflictError(PublishError):
    """The remote catalog changed after it was read."""


class PreconditionFailed(PublishError):
    """A conditional object write lost the race."""
